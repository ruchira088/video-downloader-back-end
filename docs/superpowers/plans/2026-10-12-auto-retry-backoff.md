# Automatic Retry With Backoff Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Failed scheduled downloads are retried automatically with exponential backoff and jitter, and only land in
`Error` once the attempt limit is used up.

**Architecture:** Two new `scheduled_video` columns (`retry_count`, `retry_after`) keep a row waiting to retry in
`Queued`; `acquireTask` skips rows whose `retry_after` is in the future. A pure `RetryPolicy` in the batch module
decides the next delay, and `BatchSchedulingServiceImpl.recordFailure` composes `getById` with either the new
`scheduleRetry` or the existing `setErrorById` in one `ConnectionIO` transaction.

**Tech Stack:** Scala 2.13, cats-effect, doobie (H2 in PostgreSQL mode for DAO tests, Postgres via TestContainers
for service tests), Flyway, PureConfig, circe, vulcan (Avro), ScalaTest + scalamock, sbt 2.

**Spec:** `docs/superpowers/specs/2026-10-12-auto-retry-backoff-design.md`

## Global Constraints

- The build uses `-Xfatal-warnings`: any compiler warning (unused import, unused parameter) fails the build.
- Scala stays on 2.13. No new dependencies.
- No new `SchedulingStatus` value. A row waiting to retry is `Queued`.
- Defaults: `max-attempts = 5`, `base-delay = 1 minute`, `max-delay = 30 minutes`; env overrides
  `RETRY_MAX_ATTEMPTS`, `RETRY_BASE_DELAY`, `RETRY_MAX_DELAY`. `max-attempts = 0` disables auto-retry.
- Only the `Errored` outcome in `SchedulerImpl` counts as an attempt.
- Markdown files keep lines at 120 characters or fewer.
- Commit messages follow the repo style (a sentence ending in a full stop) and end with
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Docker must be running: the batch service tests use TestContainers.
- Use `sbt testFull`, not `sbt test`, for the final full run (`test` is incremental under sbt 2).

## Review Focus

1. A large attempt number or a large `max-attempts` must not overflow the backoff arithmetic: the delay stays
   between zero and `max-delay`. Pinned in Task 3 (`RetryPolicySpec`, "never overflow").
2. A row whose `retry_after` is exactly the acquiring timestamp must be acquired, not skipped forever by an
   off-by-one. Pinned in Task 1 (`acquireTask` boundary test).
3. Deleting a download that is waiting to retry must still remove it and its error rows. Pinned in Task 1
   (delete-while-retrying test).
4. Pausing all workers (`Queued` → `WorkersPaused` → `Queued`) must keep a row's `retry_count` and `retry_after`, so
   pausing does not reset the attempt budget or skip the backoff. Pinned in Task 1 (bulk status test).
5. During a rolling deploy on Kafka, Schema Registry must accept the new `ScheduledVideoDownload` schema as
   backward-compatible with the old one. Pinned in Task 2 (Avro compatibility test).

---

### Task 1: Retry columns, model fields and DAO operations

**Files:**
- Create: `migration-application/src/main/resources/db/migration/V040__scheduled_video_retry.sql`
- Modify: `core/src/main/scala/com/ruchij/core/daos/scheduling/models/ScheduledVideoDownload.scala`
- Modify: `core/src/main/scala/com/ruchij/core/daos/scheduling/SchedulingDao.scala`
- Modify: `core/src/main/scala/com/ruchij/core/daos/scheduling/DoobieSchedulingDao.scala`
- Modify: `api/src/test/scala/com/ruchij/api/services/scheduling/ApiSchedulingServiceImplSpec.scala:133` (stub)
- Modify: `batch/src/test/scala/com/ruchij/batch/services/sync/SynchronizationServiceImplBehaviourSpec.scala:548`
  (stub)
- Modify: `openapi.yaml` (`ScheduledVideoDownload` schema, around line 1273)
- Test: `core/src/test/scala/com/ruchij/core/daos/schedulling/DoobieSchedulingDaoSpec.scala` (note the existing
  `schedulling` spelling of the test package directory)

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces:
  - `ScheduledVideoDownload(..., errorInfo: Option[ErrorInfo], retryCount: Int = 0, retryAfter: Option[Instant] = None)`
  - `SchedulingDao[F].scheduleRetry(id: String, throwable: Throwable, retryAfter: Instant, timestamp: Instant):
    F[Option[ScheduledVideoDownload]]`
  - `acquireTask(timestamp)` only returns rows with `retry_after IS NULL OR retry_after <= timestamp`.
  - `markScheduledVideoDownloadAsComplete` and `retryErroredScheduledDownloads` reset `retry_count` and
    `retry_after`; `updateSchedulingStatusById` clears `retry_after` and keeps `retry_count`.

- [ ] **Step 1: Write the failing DAO tests**

Append these cases to the end of `DoobieSchedulingDaoSpec` (before the final closing brace). They use the existing
`runTest` fixture, which inserts one `Queued` row.

```scala
  it should "schedule a retry that acquireTask skips until retryAfter is due" in runTest {
    (scheduledVideoDownload, transaction) =>
      val id = scheduledVideoDownload.videoMetadata.id

      for {
        timestamp <- Clock[IO].timestamp
        retryAfter = timestamp.plusSeconds(600)
        exception = new Exception("Connection reset")

        maybeRetrying <- transaction(DoobieSchedulingDao.scheduleRetry(id, exception, retryAfter, timestamp))

        _ <- IO.delay {
          maybeRetrying.value.status mustBe SchedulingStatus.Queued
          maybeRetrying.value.retryCount mustBe 1
          maybeRetrying.value.retryAfter.map(_.toEpochMilli) mustBe Some(retryAfter.toEpochMilli)
          maybeRetrying.value.errorInfo.value.message mustBe "Connection reset"
        }

        beforeDue <- transaction(DoobieSchedulingDao.acquireTask(timestamp))
        _ <- IO.delay { beforeDue mustBe None }

        // Review Focus 2: retry_after equal to the acquiring timestamp is due
        atDue <- transaction(DoobieSchedulingDao.acquireTask(retryAfter))
        _ <- IO.delay {
          atDue.value.videoMetadata.id mustBe id
          atDue.value.status mustBe SchedulingStatus.Acquired
          atDue.value.retryCount mustBe 1
        }
      } yield (): Unit
  }

  it should "increment the retry count on every scheduled retry" in runTest {
    (scheduledVideoDownload, transaction) =>
      val id = scheduledVideoDownload.videoMetadata.id

      for {
        timestamp <- Clock[IO].timestamp
        _ <- transaction(DoobieSchedulingDao.scheduleRetry(id, new Exception("first"), timestamp, timestamp))
        second <- transaction {
          DoobieSchedulingDao.scheduleRetry(id, new Exception("second"), timestamp, timestamp.plusMillis(1))
        }
        _ <- IO.delay {
          second.value.retryCount mustBe 2
          second.value.errorInfo.value.message mustBe "second"
        }
      } yield (): Unit
  }

  it should "reset retry state when the download completes" in runTest {
    (scheduledVideoDownload, transaction) =>
      val id = scheduledVideoDownload.videoMetadata.id

      for {
        timestamp <- Clock[IO].timestamp
        _ <- transaction(DoobieSchedulingDao.scheduleRetry(id, new Exception("blip"), timestamp, timestamp))
        completed <- transaction(DoobieSchedulingDao.markScheduledVideoDownloadAsComplete(id, timestamp.plusMillis(1)))
        _ <- IO.delay {
          completed.value.status mustBe SchedulingStatus.Completed
          completed.value.retryCount mustBe 0
          completed.value.retryAfter mustBe None
        }
      } yield (): Unit
  }

  it should "reset retry state when errored downloads are retried manually" in runTest {
    (scheduledVideoDownload, transaction) =>
      val id = scheduledVideoDownload.videoMetadata.id

      for {
        timestamp <- Clock[IO].timestamp
        _ <- transaction(DoobieSchedulingDao.scheduleRetry(id, new Exception("blip"), timestamp, timestamp))
        _ <- transaction(DoobieSchedulingDao.setErrorById(id, new Exception("final"), timestamp.plusMillis(1)))
        retried <- transaction(DoobieSchedulingDao.retryErroredScheduledDownloads(None, timestamp.plusMillis(2)))
        stored <- transaction(DoobieSchedulingDao.getById(id, None))
        _ <- IO.delay {
          retried.map(_.retryCount) mustBe Seq(0)
          retried.map(_.retryAfter) mustBe Seq(None)
          stored.value.status mustBe SchedulingStatus.Queued
          stored.value.retryCount mustBe 0
          stored.value.retryAfter mustBe None
        }
      } yield (): Unit
  }

  it should "clear retryAfter but keep retryCount when the status is set directly" in runTest {
    (scheduledVideoDownload, transaction) =>
      val id = scheduledVideoDownload.videoMetadata.id

      for {
        timestamp <- Clock[IO].timestamp
        _ <- transaction {
          DoobieSchedulingDao.scheduleRetry(id, new Exception("blip"), timestamp.plusSeconds(600), timestamp)
        }
        _ <- transaction(DoobieSchedulingDao.updateSchedulingStatusById(id, SchedulingStatus.Paused, timestamp))
        resumed <- transaction(DoobieSchedulingDao.updateSchedulingStatusById(id, SchedulingStatus.Queued, timestamp))
        acquired <- transaction(DoobieSchedulingDao.acquireTask(timestamp))
        _ <- IO.delay {
          resumed.value.retryCount mustBe 1
          resumed.value.retryAfter mustBe None
          acquired.value.videoMetadata.id mustBe id
        }
      } yield (): Unit
  }

  it should "keep retry state across a bulk WorkersPaused round trip" in runTest {
    (scheduledVideoDownload, transaction) =>
      val id = scheduledVideoDownload.videoMetadata.id

      for {
        timestamp <- Clock[IO].timestamp
        retryAfter = timestamp.plusSeconds(600)
        _ <- transaction(DoobieSchedulingDao.scheduleRetry(id, new Exception("blip"), retryAfter, timestamp))
        _ <- transaction {
          DoobieSchedulingDao.updateSchedulingStatus(SchedulingStatus.Queued, SchedulingStatus.WorkersPaused)
        }
        restored <- transaction {
          DoobieSchedulingDao.updateSchedulingStatus(SchedulingStatus.WorkersPaused, SchedulingStatus.Queued)
        }
        _ <- IO.delay {
          restored.map(_.retryCount) mustBe Seq(1)
          restored.flatMap(_.retryAfter).map(_.toEpochMilli) mustBe Seq(retryAfter.toEpochMilli)
        }
      } yield (): Unit
  }

  it should "delete a download that is waiting to retry" in runTest {
    (scheduledVideoDownload, transaction) =>
      val id = scheduledVideoDownload.videoMetadata.id

      for {
        timestamp <- Clock[IO].timestamp
        _ <- transaction(DoobieSchedulingDao.scheduleRetry(id, new Exception("blip"), timestamp, timestamp))
        deleted <- transaction(DoobieSchedulingDao.deleteById(id))
        stored <- transaction(DoobieSchedulingDao.getById(id, None))
        _ <- IO.delay {
          deleted mustBe 2 // one error row and one scheduled_video row
          stored mustBe None
        }
      } yield (): Unit
  }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `sbt "core/testOnly *DoobieSchedulingDaoSpec"`
Expected: compilation FAILS with `value scheduleRetry is not a member of object DoobieSchedulingDao` and
`value retryCount is not a member of ScheduledVideoDownload`.

- [ ] **Step 3: Add the migration**

Create `migration-application/src/main/resources/db/migration/V040__scheduled_video_retry.sql`:

```sql
ALTER TABLE scheduled_video ADD COLUMN retry_count INT NOT NULL DEFAULT 0;

ALTER TABLE scheduled_video ADD COLUMN retry_after TIMESTAMP WITH TIME ZONE NULL;
```

- [ ] **Step 4: Add the model fields**

In `ScheduledVideoDownload.scala`, append two fields after `errorInfo`. They must be last and have defaults:
existing positional constructor calls keep compiling, and vulcan turns the defaults into Avro field defaults.

```scala
final case class ScheduledVideoDownload(
  scheduledAt: Instant,
  lastUpdatedAt: Instant,
  status: SchedulingStatus,
  downloadedBytes: Long,
  videoMetadata: VideoMetadata,
  completedAt: Option[Instant],
  errorInfo: Option[ErrorInfo],
  retryCount: Int = 0,
  retryAfter: Option[Instant] = None
)
```

- [ ] **Step 5: Add `scheduleRetry` to the DAO trait**

In `SchedulingDao.scala`, directly after `setErrorById`:

```scala
  def scheduleRetry(
    id: String,
    throwable: Throwable,
    retryAfter: Instant,
    timestamp: Instant
  ): F[Option[ScheduledVideoDownload]]
```

- [ ] **Step 6: Update `DoobieSchedulingDao`**

6a. `SelectQuery`: the doobie `Read` for the case class is positional, so the two new columns go at the very end.
Change the last select line to:

```scala
        scheduled_video.completed_at, scheduled_video_error.error_message, scheduled_video_error.error_details,
        scheduled_video.retry_count, scheduled_video.retry_after
```

6b. `markScheduledVideoDownloadAsComplete`: add to its `SET` list, after `error_id = NULL`:

```scala
            error_id = NULL,
            retry_count = 0,
            retry_after = NULL
```

6c. `updateSchedulingStatusById`: add to its `SET` list, after `error_id = NULL` (`retry_count` is deliberately
kept):

```scala
            error_id = NULL,
            retry_after = NULL
```

6d. `retryErroredScheduledDownloads` (the private paged version): reset both columns in the returned copies and in
the `UPDATE`:

```scala
          val retried =
            failedScheduledVideos.toList.map {
              _.copy(
                status = SchedulingStatus.Queued,
                lastUpdatedAt = timestamp,
                completedAt = None,
                errorInfo = None,
                retryCount = 0,
                retryAfter = None
              )
            }

          (fr"""
              UPDATE scheduled_video
              SET
                status = ${SchedulingStatus.Queued},
                error_id = NULL,
                completed_at = NULL,
                last_updated_at = $timestamp,
                retry_count = 0,
                retry_after = NULL
              WHERE
            """ ++ in(fr"video_metadata_id", videoMetadataIds)).update.run
```

6e. `acquireTask`: filter out rows that are not yet due. Replace the `SELECT` with:

```scala
    sql"""
      SELECT video_metadata_id FROM scheduled_video
        WHERE status = ${SchedulingStatus.Queued}
          AND (retry_after IS NULL OR retry_after <= $timestamp)
        ORDER BY scheduled_at ASC
        LIMIT 1
   """.query[String]
```

6f. Replace `setErrorById` with a shared error-row helper, the unchanged terminal path, and the new `scheduleRetry`:

```scala
  override def setErrorById(
    id: String,
    throwable: Throwable,
    timestamp: Instant
  ): ConnectionIO[Option[ScheduledVideoDownload]] =
    insertError(id, throwable, timestamp)
      .flatMap { errorId =>
        sql"""
          UPDATE scheduled_video
            SET status = ${SchedulingStatus.Error}, last_updated_at = $timestamp, error_id = $errorId
            WHERE video_metadata_id = $id
         """.update.run.one
      }
      .productR(getById(id, None))

  override def scheduleRetry(
    id: String,
    throwable: Throwable,
    retryAfter: Instant,
    timestamp: Instant
  ): ConnectionIO[Option[ScheduledVideoDownload]] =
    insertError(id, throwable, timestamp)
      .flatMap { errorId =>
        sql"""
          UPDATE scheduled_video
            SET
              status = ${SchedulingStatus.Queued},
              last_updated_at = $timestamp,
              error_id = $errorId,
              retry_count = retry_count + 1,
              retry_after = $retryAfter
            WHERE video_metadata_id = $id
         """.update.run.one
      }
      .productR(getById(id, None))

  private def insertError(id: String, throwable: Throwable, timestamp: Instant): ConnectionIO[String] = {
    val errorId = s"$id-${timestamp.toEpochMilli}"

    sql"""
         INSERT INTO scheduled_video_error (id, created_at, video_id, error_message, error_details)
            VALUES (
              $errorId,
              $timestamp,
              $id,
              ${rootException(throwable)},
              ${throwable.getStackTrace.map(_.toString).mkString("\n")}
            )
     """.update.run.one.as(errorId)
  }
```

- [ ] **Step 7: Implement `scheduleRetry` in the two test stubs**

In `ApiSchedulingServiceImplSpec.scala`, next to the stub's `setErrorById` (line 133):

```scala
    override def scheduleRetry(
      id: String,
      throwable: Throwable,
      retryAfter: Instant,
      timestamp: Instant
    ): IO[Option[ScheduledVideoDownload]] = IO.pure(None)
```

In `SynchronizationServiceImplBehaviourSpec.scala`, next to the stub's `setErrorById` (line 548):

```scala
    override def scheduleRetry(
      id: String,
      throwable: Throwable,
      retryAfter: Instant,
      timestamp: Instant
    ): IO[Option[ScheduledVideoDownload]] = notImplemented
```

- [ ] **Step 8: Document the fields in `openapi.yaml`**

In the `ScheduledVideoDownload` schema, after the `errorInfo` property (its last line is
`            details:\n              type: string`), add:

```yaml
        retryCount:
          type: integer
          format: int32
          description: Failures retried automatically so far. Reset on completion and by POST /schedule/retry-failed.
        retryAfter:
          type: string
          format: date-time
          nullable: true
          description: Earliest time a Queued download that is waiting to retry can be picked up.
```

- [ ] **Step 9: Run the tests to verify they pass**

Run: `sbt "core/testOnly *DoobieSchedulingDaoSpec"`
Expected: PASS, including every existing case.

Run: `sbt compile "api/Test/compile" "batch/Test/compile"`
Expected: success, which confirms the stubs compile.

- [ ] **Step 10: Commit**

```bash
git add migration-application/src/main/resources/db/migration/V040__scheduled_video_retry.sql \
  core/src/main/scala/com/ruchij/core/daos/scheduling \
  core/src/test/scala/com/ruchij/core/daos/schedulling/DoobieSchedulingDaoSpec.scala \
  api/src/test/scala/com/ruchij/api/services/scheduling/ApiSchedulingServiceImplSpec.scala \
  batch/src/test/scala/com/ruchij/batch/services/sync/SynchronizationServiceImplBehaviourSpec.scala \
  openapi.yaml
git commit -m "Add retry columns to scheduled videos and a scheduleRetry DAO operation that acquireTask honours.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Messaging compatibility for the new fields

**Files:**
- Modify: `core/src/main/scala/com/ruchij/core/messaging/MessagingTopic.scala`
- Test: `core/src/test/scala/com/ruchij/core/messaging/MessagingTopicJsonCodecSpec.scala`
- Test: `core/src/test/scala/com/ruchij/core/messaging/kafka/MessagingTopicSpec.scala`

**Interfaces:**
- Consumes: `ScheduledVideoDownload.retryCount` / `retryAfter` from Task 1.
- Produces: `ScheduledVideoDownloadTopic.jsonCodec` decodes JSON without `retryCount` as `retryCount = 0`. The Avro
  schema is backward-compatible with the pre-retry schema.

- [ ] **Step 1: Write the failing tests**

In `MessagingTopicJsonCodecSpec.scala`, add after the existing `ScheduledVideoDownloadTopic` case. It uses the
imports the file already has.

```scala
  it should "decode a ScheduledVideoDownload published before retries existed" in {
    val scheduledVideoDownload =
      ScheduledVideoDownload(
        timestamp,
        timestamp,
        SchedulingStatus.Queued,
        0,
        VideoMetadata(
          uri"https://spankbang.com/abc/video/title",
          "video-id",
          CustomVideoSite.SpankBang,
          "Sample title",
          FiniteDuration(212000, TimeUnit.MILLISECONDS),
          4096,
          FileResource("thumbnail-id", timestamp, "/opt/images/thumbnail.jpg", MediaType.image.jpeg, 256)
        ),
        None,
        None,
        retryCount = 3,
        retryAfter = Some(timestamp)
      )

    val topic = MessagingTopic.ScheduledVideoDownloadTopic
    val legacyJson = topic.jsonCodec(scheduledVideoDownload).mapObject(_.remove("retryCount").remove("retryAfter"))

    decode[ScheduledVideoDownload](legacyJson.noSpaces)(topic.jsonCodec) mustBe
      Right(scheduledVideoDownload.copy(retryCount = 0, retryAfter = None))
  }
```

In `kafka/MessagingTopicSpec.scala`, add these imports:

```scala
import org.apache.avro.{Schema, SchemaCompatibility}
import org.apache.avro.SchemaCompatibility.SchemaCompatibilityType

import scala.jdk.CollectionConverters._
```

and this case after the existing `ScheduledVideoDownloadTopic` cases:

```scala
  it should "read records written with the schema from before retries existed" in {
    val schema: Schema =
      MessagingTopic.ScheduledVideoDownloadTopic.avroCodec.schema.fold(error => fail(error.message), identity)

    schema.getField("retryCount") must not be null
    schema.getField("retryAfter") must not be null

    val legacyFields =
      schema.getFields.asScala.toList
        .filterNot(field => Set("retryCount", "retryAfter").contains(field.name()))
        .map(field => new Schema.Field(field, field.schema()))

    val legacySchema =
      Schema.createRecord(schema.getName, schema.getDoc, schema.getNamespace, false, legacyFields.asJava)

    // Schema Registry's default BACKWARD check: the new schema must be able to read data written with the old one
    SchemaCompatibility.checkReaderWriterCompatibility(schema, legacySchema).getType mustBe
      SchemaCompatibilityType.COMPATIBLE
  }
```

- [ ] **Step 2: Run the tests to verify the JSON test fails**

Run: `sbt "core/testOnly *MessagingTopicJsonCodecSpec *kafka.MessagingTopicSpec"`
Expected: the JSON legacy test FAILS with a `DecodingFailure` for missing field `retryCount`. The Avro test already
passes, because the Task 1 defaults are enough. It stays as a regression guard: removing a default makes it fail.

- [ ] **Step 3: Make the JSON decoder default `retryCount`**

In `MessagingTopic.scala`, add `Json` to the circe import:

```scala
import io.circe.{Codec => JsonCodec, Json}
```

and replace the `ScheduledVideoDownloadTopic.jsonCodec` definition:

```scala
    // Messages from replicas deployed before retries existed have no retryCount. Plain circe derivation ignores
    // Scala default values, so supply the default before decoding. A missing retryAfter already decodes as None.
    override val jsonCodec: JsonCodec[ScheduledVideoDownload] = {
      val derived = deriveJsonCodec[ScheduledVideoDownload]

      JsonCodec.from(
        derived.prepare {
          _.withFocus {
            _.mapObject(json => if (json.contains("retryCount")) json else json.add("retryCount", Json.fromInt(0)))
          }
        },
        derived
      )
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `sbt "core/testOnly *MessagingTopicJsonCodecSpec *kafka.MessagingTopicSpec *AvroCodecsSpec"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/scala/com/ruchij/core/messaging/MessagingTopic.scala \
  core/src/test/scala/com/ruchij/core/messaging/MessagingTopicJsonCodecSpec.scala \
  core/src/test/scala/com/ruchij/core/messaging/kafka/MessagingTopicSpec.scala
git commit -m "Keep scheduled video download messages compatible across a rolling deploy that adds retry fields.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Retry configuration and `RetryPolicy`

**Files:**
- Create: `batch/src/main/scala/com/ruchij/batch/config/RetryConfiguration.scala`
- Create: `batch/src/main/scala/com/ruchij/batch/services/scheduling/RetryPolicy.scala`
- Modify: `batch/src/main/scala/com/ruchij/batch/config/BatchServiceConfiguration.scala`
- Modify: `batch/src/main/resources/application.conf`
- Modify: `README.md` (Batch Service environment variables table, around line 236)
- Test: `batch/src/test/scala/com/ruchij/batch/services/scheduling/RetryPolicySpec.scala`
- Test: `batch/src/test/scala/com/ruchij/batch/config/BatchServiceConfigurationSpec.scala`

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces:
  - `final case class RetryConfiguration(maxAttempts: Int, baseDelay: FiniteDuration, maxDelay: FiniteDuration)`
    with `RetryConfiguration.Default` (5, 1 minute, 30 minutes) and `RetryConfiguration.Disabled` (0 attempts).
  - `BatchServiceConfiguration.retryConfiguration: RetryConfiguration = RetryConfiguration.Default`, the last
    field. The default keeps existing constructor calls and config files without the block working.
  - `final case class RetryPolicy(configuration: RetryConfiguration)` with
    `def nextDelay(attempt: Int, jitter: Double): Option[FiniteDuration]`.

> **Human contribution point (learning mode):** after Step 3 creates `RetryPolicy.scala` with the signature and a
> `???` body, hand `nextDelay` to the human to write (5-10 lines) and stop until they have. The jitter strategy is
> their call: full jitter, equal jitter, or another. The tests in Step 1 are written to accept any strategy that
> keeps the delay within bounds and spreads retries out. The reference implementation in Step 4 is used only if
> the human asks for it.

- [ ] **Step 1: Write the failing `RetryPolicySpec`**

Create `batch/src/test/scala/com/ruchij/batch/services/scheduling/RetryPolicySpec.scala`:

```scala
package com.ruchij.batch.services.scheduling

import com.ruchij.batch.config.RetryConfiguration
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.duration._

class RetryPolicySpec extends AnyFlatSpec with Matchers with OptionValues {
  private val configuration = RetryConfiguration(maxAttempts = 5, baseDelay = 1.minute, maxDelay = 30.minutes)
  private val retryPolicy = RetryPolicy(configuration)
  private val jitters = List(0.0, 0.25, 0.5, 0.75, 0.999999)

  // The largest delay any strategy may pick for an attempt: base * 2^attempt, capped at maxDelay
  private def ceiling(attempt: Int): FiniteDuration =
    (configuration.baseDelay * math.pow(2, attempt).toLong).min(configuration.maxDelay)

  "nextDelay" should "give up once the attempt limit is reached" in {
    retryPolicy.nextDelay(5, 0.5) mustBe None
    retryPolicy.nextDelay(6, 0.5) mustBe None
  }

  it should "never retry when maxAttempts is 0" in {
    RetryPolicy(RetryConfiguration.Disabled).nextDelay(0, 0.5) mustBe None
  }

  it should "keep every delay between zero and the backoff ceiling for its attempt" in {
    for {
      attempt <- 0 until configuration.maxAttempts
      jitter <- jitters
    } {
      val delay = retryPolicy.nextDelay(attempt, jitter).value
      delay must be >= Duration.Zero
      delay must be <= ceiling(attempt)
    }
  }

  it should "let later attempts wait longer at the same jitter" in {
    for (jitter <- jitters.filter(_ > 0)) {
      retryPolicy.nextDelay(3, jitter).value must be > retryPolicy.nextDelay(0, jitter).value
    }
  }

  it should "cap delays at maxDelay" in {
    val capped = RetryPolicy(configuration.copy(maxAttempts = 20))

    for (jitter <- jitters) {
      capped.nextDelay(15, jitter).value must be <= configuration.maxDelay
    }
  }

  // Review Focus 1: base * 2^attempt overflows a Long well before attempt 100
  it should "never overflow for large attempt numbers" in {
    val many = RetryPolicy(configuration.copy(maxAttempts = Int.MaxValue))

    for {
      attempt <- List(62, 63, 64, 100, 1_000, Int.MaxValue - 1)
      jitter <- jitters
    } {
      val delay = many.nextDelay(attempt, jitter).value
      delay must be >= Duration.Zero
      delay must be <= configuration.maxDelay
    }
  }

  it should "spread retries that fail at the same moment" in {
    retryPolicy.nextDelay(2, 0.1).value must not equal retryPolicy.nextDelay(2, 0.9).value
  }

  it should "be deterministic for the same attempt and jitter" in {
    retryPolicy.nextDelay(2, 0.42) mustBe retryPolicy.nextDelay(2, 0.42)
  }
}
```

Add a config test to `BatchServiceConfigurationSpec.scala`, after the first case. It reuses the existing first
case's HOCON by appending a `retry-configuration` block. Move that case's `configSource` string, unchanged and
still an `s"""` interpolated string with its `$${?VAR}` escapes, into a class-level `private val baseConfigSource`,
and make the
first case use it, so the new cases share it:

```scala
  it should "default the retry configuration when the block is absent" in runIO {
    BatchServiceConfiguration.parse[IO](ConfigSource.string(baseConfigSource)).flatMap { configuration =>
      IO.delay { configuration.retryConfiguration mustBe RetryConfiguration.Default }
    }
  }

  it should "parse the retry configuration" in runIO {
    val configSource =
      baseConfigSource +
        """
          retry-configuration {
            max-attempts = 3
            base-delay = 30 seconds
            max-delay = 10 minutes
          }
        """

    BatchServiceConfiguration.parse[IO](ConfigSource.string(configSource)).flatMap { configuration =>
      IO.delay {
        configuration.retryConfiguration mustBe RetryConfiguration(3, 30.seconds, 10.minutes)
      }
    }
  }
```

and add `import scala.concurrent.duration._` to that spec's imports.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `sbt "batch/testOnly *RetryPolicySpec *BatchServiceConfigurationSpec"`
Expected: compilation FAILS with `not found: type RetryConfiguration` and `not found: value RetryPolicy`.

- [ ] **Step 3: Add the configuration and the policy skeleton**

Create `batch/src/main/scala/com/ruchij/batch/config/RetryConfiguration.scala`:

```scala
package com.ruchij.batch.config

import scala.concurrent.duration.{DurationInt, FiniteDuration}

final case class RetryConfiguration(maxAttempts: Int, baseDelay: FiniteDuration, maxDelay: FiniteDuration)

object RetryConfiguration {
  val Default: RetryConfiguration = RetryConfiguration(maxAttempts = 5, baseDelay = 1.minute, maxDelay = 30.minutes)

  val Disabled: RetryConfiguration = Default.copy(maxAttempts = 0)
}
```

Create `batch/src/main/scala/com/ruchij/batch/services/scheduling/RetryPolicy.scala`:

```scala
package com.ruchij.batch.services.scheduling

import com.ruchij.batch.config.RetryConfiguration

import scala.concurrent.duration.FiniteDuration

final case class RetryPolicy(configuration: RetryConfiguration) {

  /**
    * The delay before retrying a failed download, or None to give up and leave it in Error.
    *
    * @param attempt how many failures of this download have already been retried (its current retryCount)
    * @param jitter  a uniform random value in [0, 1), drawn by the caller so this function stays deterministic
    */
  def nextDelay(attempt: Int, jitter: Double): Option[FiniteDuration] = ???
}
```

In `BatchServiceConfiguration.scala`, add the field last, with its default:

```scala
  httpProxyConfiguration: Option[HttpProxyConfiguration],
  retryConfiguration: RetryConfiguration = RetryConfiguration.Default
)
```

PureConfig's generic derivation uses case-class default arguments when a key is missing, so config sources without
`retry-configuration` still parse.

In `batch/src/main/resources/application.conf`, after the `worker-configuration` block:

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

In `README.md`, Batch Service table, after the `END_TIME` row:

```markdown
| `RETRY_MAX_ATTEMPTS` | Automatic retries of a failed download before it stays in Error (0 disables) | `5` |
| `RETRY_BASE_DELAY` | Backoff before the first retry, doubling each attempt | `1 minute` |
| `RETRY_MAX_DELAY` | Upper bound on the backoff between retries | `30 minutes` |
```

- [ ] **Step 4: Implement `nextDelay`**

This is the human contribution point. Ask the human to write the body and wait for them. If they ask for the
reference, use this equal-jitter version: it always waits at least half the backoff, and the other half is random.
`math.pow` on `Double` returns `Infinity` instead of overflowing, and `min` then caps it.

```scala
  def nextDelay(attempt: Int, jitter: Double): Option[FiniteDuration] =
    if (attempt < 0 || attempt >= configuration.maxAttempts) None
    else {
      val backoffMillis =
        math.min(configuration.baseDelay.toMillis * math.pow(2, attempt), configuration.maxDelay.toMillis.toDouble)
      val halfMillis = backoffMillis / 2

      Some(FiniteDuration((halfMillis + halfMillis * jitter).toLong, TimeUnit.MILLISECONDS))
    }
```

with `import java.util.concurrent.TimeUnit` added to the file.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `sbt "batch/testOnly *RetryPolicySpec *BatchServiceConfigurationSpec"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add batch/src/main/scala/com/ruchij/batch/config \
  batch/src/main/scala/com/ruchij/batch/services/scheduling/RetryPolicy.scala \
  batch/src/main/resources/application.conf \
  batch/src/test/scala/com/ruchij/batch/services/scheduling/RetryPolicySpec.scala \
  batch/src/test/scala/com/ruchij/batch/config/BatchServiceConfigurationSpec.scala \
  README.md
git commit -m "Add retry configuration and a backoff policy with jitter for failed downloads.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: `recordFailure` in the batch service and scheduler

**Files:**
- Modify: `batch/src/main/scala/com/ruchij/batch/services/scheduling/BatchSchedulingService.scala:18`
- Modify: `batch/src/main/scala/com/ruchij/batch/services/scheduling/BatchSchedulingServiceImpl.scala`
- Modify: `batch/src/main/scala/com/ruchij/batch/services/scheduler/SchedulerImpl.scala:163`
- Modify: `batch/src/main/scala/com/ruchij/batch/BatchApp.scala:146`
- Modify: `batch/src/test/scala/com/ruchij/batch/test/stubs/BatchStubs.scala:46`
- Modify: `batch/src/test/scala/com/ruchij/batch/services/scheduler/SchedulerImplSpec.scala:75`
- Test: `batch/src/test/scala/com/ruchij/batch/services/scheduling/BatchSchedulingServiceImplSpec.scala`

**Interfaces:**
- Consumes: `SchedulingDao.scheduleRetry` and the model fields (Task 1); `RetryPolicy`, `RetryConfiguration`,
  `BatchServiceConfiguration.retryConfiguration` (Task 3).
- Produces: `BatchSchedulingService[F].recordFailure(id: String, throwable: Throwable): F[ScheduledVideoDownload]`,
  which replaces `setErrorById`. `BatchSchedulingServiceImpl` gains the constructor parameters
  `retryPolicy: RetryPolicy, jitterGenerator: RandomGenerator[F, Double]` after `storageConfiguration`.

- [ ] **Step 1: Write the failing service tests**

In `BatchSchedulingServiceImplSpec.scala`, add these imports:

```scala
import com.ruchij.batch.config.RetryConfiguration
import com.ruchij.core.types.RandomGenerator
```

Change `createTestFixture` to take a retry configuration, defaulting to disabled so the existing error tests keep
asserting `Error`:

```scala
  def createTestFixture(
    retryConfiguration: RetryConfiguration = RetryConfiguration.Disabled
  )(implicit transactor: ConnectionIO ~> IO): TestFixture = {
```

Pass the two new arguments in its constructor call, and in **every other** `new BatchSchedulingServiceImpl[IO,
ConnectionIO](...)` in this file (lines 89, 374, 433, 464, 497, 532, 560), after `storageConfiguration`:

```scala
      storageConfiguration,
      RetryPolicy(retryConfiguration),        // in createTestFixture
      RandomGenerator.eval(IO.pure(0.5))
```

At the other seven sites, use `RetryPolicy(RetryConfiguration.Disabled)` in place of `RetryPolicy(retryConfiguration)`.
Existing calls `createTestFixture` (no parentheses) become `createTestFixture()`.

Rename the existing `"setErrorById" should ...` cases to `"recordFailure" should "set Error once retries are
disabled and publish the update"`, and change their calls from `setErrorById(` to `recordFailure(`. Then add:

```scala
  it should "schedule a retry with backoff while attempts remain, and publish the update" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]
    val retryConfiguration = RetryConfiguration(maxAttempts = 2, baseDelay = 1.minute, maxDelay = 30.minutes)

    batchServiceProvider.transactor.use { implicit transactor =>
      val fixture = createTestFixture(retryConfiguration)
      val expectedDelay = RetryPolicy(retryConfiguration).nextDelay(0, 0.5).value

      for {
        scheduledVideo <- DataGenerators.scheduledVideoDownload[IO].generate
        _ <- transactor(insertScheduledVideo(scheduledVideo))

        _ = (fixture.scheduledVideoDownloadPubSub.publishOne _)
          .expects(where { (update: ScheduledVideoDownload) => update.status == SchedulingStatus.Queued })
          .returning(IO.unit)

        updated <-
          fixture.batchSchedulingService.recordFailure(scheduledVideo.videoMetadata.id, new RuntimeException("reset"))
        _ <- IO.delay {
          updated.status mustBe SchedulingStatus.Queued
          updated.retryCount mustBe 1
          updated.errorInfo.value.message must include("reset")
          updated.retryAfter.value.toEpochMilli - updated.lastUpdatedAt.toEpochMilli mustBe expectedDelay.toMillis
        }
      } yield ()
    }
  }

  it should "set Error once the attempt limit is used up" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]
    val retryConfiguration = RetryConfiguration(maxAttempts = 1, baseDelay = 1.minute, maxDelay = 30.minutes)

    batchServiceProvider.transactor.use { implicit transactor =>
      val fixture = createTestFixture(retryConfiguration)

      for {
        scheduledVideo <- DataGenerators.scheduledVideoDownload[IO].generate
        _ <- transactor(insertScheduledVideo(scheduledVideo))

        _ = (fixture.scheduledVideoDownloadPubSub.publishOne _).expects(*).returning(IO.unit).twice()

        id = scheduledVideo.videoMetadata.id
        first <- fixture.batchSchedulingService.recordFailure(id, new RuntimeException("one"))
        // Error-row IDs are {id}-{epochMillis}, so the second failure needs a later millisecond
        _ <- IO.sleep(5.millis)
        second <- fixture.batchSchedulingService.recordFailure(id, new RuntimeException("two"))
        _ <- IO.delay {
          first.status mustBe SchedulingStatus.Queued
          second.status mustBe SchedulingStatus.Error
          second.retryCount mustBe 1
          second.errorInfo.value.message must include("two")
        }
      } yield ()
    }
  }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `sbt "batch/testOnly *BatchSchedulingServiceImplSpec"`
Expected: compilation FAILS with `value recordFailure is not a member of BatchSchedulingServiceImpl` and
`too many arguments for constructor BatchSchedulingServiceImpl`.

- [ ] **Step 3: Rename the trait method**

In `BatchSchedulingService.scala`, replace line 18:

```scala
  /** Records a failed download: schedules a retry with backoff while attempts remain, otherwise sets Error. */
  def recordFailure(id: String, throwable: Throwable): F[ScheduledVideoDownload]
```

- [ ] **Step 4: Implement `recordFailure`**

In `BatchSchedulingServiceImpl.scala`, add these imports:

```scala
import com.ruchij.core.types.RandomGenerator
```

add the constructor parameters after `storageConfiguration`:

```scala
  storageConfiguration: StorageConfiguration,
  retryPolicy: RetryPolicy,
  jitterGenerator: RandomGenerator[F, Double]
)(implicit transaction: T ~> F)
```

and replace `setErrorById` with:

```scala
  override def recordFailure(id: String, throwable: Throwable): F[ScheduledVideoDownload] =
    for {
      timestamp <- Clock[F].timestamp
      jitter <- jitterGenerator.generate
      updated <- OptionT {
        transaction {
          OptionT(schedulingDao.getById(id, None)).flatMapF { current =>
            retryPolicy.nextDelay(current.retryCount, jitter) match {
              case Some(delay) =>
                schedulingDao.scheduleRetry(id, throwable, timestamp.plusMillis(delay.toMillis), timestamp)

              case None => schedulingDao.setErrorById(id, throwable, timestamp)
            }
          }.value
        }
      }.getOrElseF(ApplicativeError[F, Throwable].raiseError(notFound(id)))
      _ <- logFailure(updated, throwable)
      _ <- scheduledVideoDownloadPubSub.publishOne(updated)
    } yield updated

  private def logFailure(scheduledVideoDownload: ScheduledVideoDownload, throwable: Throwable): F[Unit] =
    if (scheduledVideoDownload.status == SchedulingStatus.Queued)
      logger.warn[F] {
        s"Download failed, retrying. id=${scheduledVideoDownload.videoMetadata.id}, " +
          s"attempt=${scheduledVideoDownload.retryCount}/${retryPolicy.configuration.maxAttempts}, " +
          s"retryAfter=${scheduledVideoDownload.retryAfter.getOrElse("-")}, error=${throwable.getMessage}"
      }
    else Applicative[F].unit
```

The give-up path keeps the existing `logger.error` in `SchedulerImpl`, which logs the stack trace.

- [ ] **Step 5: Update the scheduler, the stubs and the wiring**

`SchedulerImpl.scala:163`: change `.setErrorById(scheduledVideoDownload.videoMetadata.id, exception)` to
`.recordFailure(scheduledVideoDownload.videoMetadata.id, exception)`.

`BatchStubs.scala:46`: rename the override to
`override def recordFailure(id: String, throwable: Throwable): IO[ScheduledVideoDownload] =`, keeping its body.

`SchedulerImplSpec.scala:75`: rename the override to
`override def recordFailure(id: String, throwable: Throwable): IO[ScheduledVideoDownload] = {`, keeping its body
and the `erroredTasks` field. The existing tests "record an error against the task ... when the executor fails"
and "pause the active download ..." (`erroredTasks mustBe empty`) now pin the scheduler wiring: a failure calls
`recordFailure`, and a pause does not.

`BatchApp.scala:146`: pass the policy and a jitter generator:

```scala
          batchSchedulingService = new BatchSchedulingServiceImpl[F, ConnectionIO](
            downloadProgressPublisher,
            workerStatusUpdatesSubscriber,
            scheduledVideoDownloadPubSub,
            repositoryService,
            DoobieSchedulingDao,
            workerDao,
            DoobieVideoMetadataDao,
            batchServiceConfiguration.storageConfiguration,
            RetryPolicy(batchServiceConfiguration.retryConfiguration),
            RandomGenerator[F, Double](Random.nextDouble())
          )
```

with imports `com.ruchij.batch.services.scheduling.RetryPolicy`, `com.ruchij.core.types.RandomGenerator` and
`scala.util.Random` (skip any already imported). `DevelopmentApp` goes through `BatchApp.program` and its
`BatchServiceConfiguration` picks up `RetryConfiguration.Default`, so it needs no change.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `sbt "batch/testOnly *BatchSchedulingServiceImplSpec *SchedulerImplSpec *BatchAppSpec"`
Expected: PASS.

- [ ] **Step 7: Run the full suite**

Run: `sbt testFull`
Expected: PASS for all modules, with no new failures against the last known total (1,671 tests passing, 8 ignored).

- [ ] **Step 8: Commit**

```bash
git add batch/src/main/scala/com/ruchij/batch \
  batch/src/test/scala/com/ruchij/batch/services/scheduling/BatchSchedulingServiceImplSpec.scala \
  batch/src/test/scala/com/ruchij/batch/services/scheduler/SchedulerImplSpec.scala \
  batch/src/test/scala/com/ruchij/batch/test/stubs/BatchStubs.scala
git commit -m "Retry failed downloads automatically with backoff before marking them as errored.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
