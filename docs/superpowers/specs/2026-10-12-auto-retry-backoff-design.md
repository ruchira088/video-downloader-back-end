# Scheduled downloads: automatic retry with backoff — design

Date: 2026-10-12
Status: draft, awaiting review

## Goal

Failed downloads should recover on their own from transient network failures (connection resets, timeouts, 5xx
from the source site) instead of sitting in `Error` until someone calls `POST /schedule/retry-failed`.

### Decisions

- **Every** failure is retried. There is no exception classification; permanent errors spend a few cheap
  attempts before giving up.
- A download waiting for its next attempt stays **`Queued`**, with new `retry_count` and `retry_after` columns.
  No new `SchedulingStatus` value, so the fallback-api, its DynamoDB copy and the front end's status handling are
  unchanged.
- Exponential backoff with jitter: about 1 minute base, doubling, capped at about 30 minutes, at most 5 retries.
  After that the row goes to `Error` as it does today.
- The policy is a pure function in the batch module (`RetryPolicy`), composed with DAO primitives in a single
  `ConnectionIO` transaction by `BatchSchedulingServiceImpl`.
- Settings live in batch `application.conf` with env-var overrides. They are not runtime-tunable via
  `ConfigurationService`.
- Only the `Errored` outcome of a work execution counts as an attempt. Pause, cancel, `WorkersPaused` and `Stale`
  timeouts do not.

### Success criteria

- A download that fails once or twice from a network blip completes without manual action.
- Logs show each retry with its attempt number, limit, next attempt time and error.
- Rolling deploys across the API and batch replicas keep working on every `PUBSUB_TYPE` (Kafka, Redis, Doobie).

### Out of scope

Exception classification, per-site retry policies, runtime-tunable settings, a separate retry status, and any
fallback-api change.

## Schema

`migration-application/src/main/resources/db/migration/V040__scheduled_video_retry.sql`:

```sql
ALTER TABLE scheduled_video ADD COLUMN retry_count INT NOT NULL DEFAULT 0;
ALTER TABLE scheduled_video ADD COLUMN retry_after TIMESTAMPTZ NULL;
```

Existing rows get `retry_count = 0` and no `retry_after`, so no backfill is needed. No index on `retry_after`:
`acquireTask` already filters on `status`, and the queue is small.

## Model

Two fields are appended to `ScheduledVideoDownload`, with Scala defaults so the existing constructor call sites
compile unchanged:

```scala
retryCount: Int = 0,
retryAfter: Option[Instant] = None
```

- **Avro (Kafka):** vulcan's `Codec.derive` turns the Scala defaults into Avro field defaults, so the new schema
  is backward-compatible in Schema Registry.
- **JSON (Redis, Doobie):** the circe codec in `MessagingTopic` ignores Scala defaults, so a message from an old
  replica, without `retryCount`, would fail to decode. The `ScheduledVideoDownload` JSON decoder is overridden so
  that a missing `retryCount` decodes as `0`. A missing `retryAfter` already decodes as `None`.
- **REST:** the routes use `circe.generic.auto`, so `retryCount` and `retryAfter` appear in responses.
  `openapi.yaml` documents both on the `ScheduledVideoDownload` schema.

## DAO (`SchedulingDao` / `DoobieSchedulingDao`)

- `SelectQuery` also selects `retry_count` and `retry_after`.
- `acquireTask` adds `AND (retry_after IS NULL OR retry_after <= $timestamp)` and still orders by `scheduled_at`.
- New `scheduleRetry(id, throwable, retryAfter, timestamp)` inserts a `scheduled_video_error` row, then sets
  `status = Queued`, `retry_count = retry_count + 1`, `retry_after`, `error_id` and `last_updated_at`.
- `setErrorById` is unchanged; its error-row insert moves into a private helper shared with `scheduleRetry`.
- `markScheduledVideoDownloadAsComplete` and `retryErroredScheduledDownloads` also reset `retry_count = 0` and
  `retry_after = NULL`.

The increment happens in SQL, not in Scala, so it does not depend on the in-memory copy being current.

While a retry is waiting, `error_id` points at the latest error, so clients can show it. `acquireTask` keeps its
existing `error_id = NULL` when the attempt starts. Error rows are never deleted, so the history stays intact.

## Retry policy and configuration

`batch/src/main/resources/application.conf`:

```hocon
retry-configuration {
  max-attempts = 5
  max-attempts = ${?RETRY_MAX_ATTEMPTS}

  base-delay = 1 minute
  base-delay = ${?RETRY_BASE_DELAY}

  max-delay = 30 minutes
  max-delay = ${?RETRY_MAX_DELAY}
}
```

```scala
final case class RetryConfiguration(maxAttempts: Int, baseDelay: FiniteDuration, maxDelay: FiniteDuration)
```

It is added to `BatchServiceConfiguration` as `retryConfiguration`. `max-attempts = 0` turns auto-retry off.

`batch/src/main/scala/com/ruchij/batch/services/scheduling/RetryPolicy.scala`:

```scala
final case class RetryPolicy(configuration: RetryConfiguration) {
  def nextDelay(attempt: Int, jitter: Double): Option[FiniteDuration]
}
```

- `attempt` is the row's current `retryCount`, i.e. how many failures have already been retried.
- `jitter` is a uniform value in `[0, 1)`, drawn by the caller, so the function is deterministic.
- It returns `None` once `attempt >= maxAttempts`, and otherwise a delay of at most `maxDelay`.
- The jitter strategy (full, equal, or another) is chosen during implementation and documented in the code. Any
  choice must spread retries that failed at the same moment across the 3 batch replicas.

## Batch service

`BatchSchedulingServiceImpl` gains two constructor parameters: `retryPolicy: RetryPolicy` and
`jitterGenerator: RandomGenerator[F, Double]`. Production passes
`RandomGenerator[F, Double](Random.nextDouble())`; tests pass a constant.

`BatchSchedulingService.setErrorById` is renamed to `recordFailure(id, throwable)`, since it no longer always means
`Error`:

```
F: timestamp <- Clock[F].timestamp; jitter <- jitterGenerator.generate
T: current <- schedulingDao.getById(id, None)
   retryPolicy.nextDelay(current.retryCount, jitter) match
     Some(delay) => schedulingDao.scheduleRetry(id, throwable, timestamp + delay, timestamp)
     None        => schedulingDao.setErrorById(id, throwable, timestamp)
F: scheduledVideoDownloadPubSub.publishOne(updated)
```

The read and the write run in one `transaction(...)`. Only the worker that owns the row can fail it, so no row
lock is needed.

A retry logs at `warn`, e.g. `Download failed, retrying. id=..., attempt=2/5, retryAfter=..., error=...`. Giving up
keeps today's `error` log. `BatchApp` and `DevelopmentApp` build `RetryPolicy(configuration.retryConfiguration)` and
the jitter generator.

## Scheduler

In `SchedulerImpl`, the `Errored` branch calls `recordFailure` instead of `setErrorById`. Nothing else changes. The
`Canceled` and `PausedVideoDownload` branches do not count as attempts. The executor's interrupt filter, which
watches for `Error` status updates, is unaffected, because the work execution has already ended when
`recordFailure` publishes.

## API

- When the API moves a row back to `Queued` because a user resumed it, `retry_after` is cleared and `retry_count`
  is kept: the user decides when, and the attempt limit still applies.
- `POST /schedule/retry-failed` resets both columns, through the DAO change above.
- No new endpoints.

## Testing

Test-driven, in the existing specs where they exist:

- New `RetryPolicySpec` (batch): delays grow with the attempt; capped at `maxDelay`; `None` once
  `attempt >= maxAttempts`; `maxAttempts = 0` never retries; jitter of 0.0 and close to 1.0 stays in bounds.
- `DoobieSchedulingDaoSpec` (core): `acquireTask` skips a row whose `retry_after` is in the future and picks it
  once due; `scheduleRetry` increments the count and writes an error row; completion and `retry-failed` reset
  both columns.
- `BatchSchedulingServiceImplSpec`: below the limit, `Queued` with `retryAfter = now + delay`, published; at the
  limit, `Error`. Uses a fixed clock and constant jitter.
- `SchedulerImplSpec`: an `Errored` outcome calls `recordFailure`.
- `SchedulerIntegrationSpec`: a download that fails once then succeeds completes, if the existing harness can fake
  a flaky downloader cheaply.
- Messaging codec test: JSON without `retryCount` decodes with `retryCount = 0`.
- API scheduling tests: resuming a paused row clears `retry_after` and keeps `retry_count`.

## Documentation

- `README.md`, Batch Service environment variables table: `RETRY_MAX_ATTEMPTS`, `RETRY_BASE_DELAY`,
  `RETRY_MAX_DELAY`.
- `openapi.yaml`: `retryCount` and `retryAfter` on `ScheduledVideoDownload`.
