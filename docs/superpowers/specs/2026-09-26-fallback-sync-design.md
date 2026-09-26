# Fallback API: scheduled video sync — design

Date: 2026-09-26
Status: draft, awaiting review

## Goal

`fallback-api/` is an AWS SAM stack that serves users when the main video-downloader-back-end API (home-hosted)
is unavailable. Users can list their scheduled videos and schedule new ones on it. To make that possible, the
fallback's DynamoDB table keeps a full copy of the scheduled videos, synced both ways over two SQS queues.

### Decisions

- The main Postgres DB is the source of truth. DynamoDB is a replica that also accepts new schedule requests.
- The fallback accepts **new schedules only**. It never edits, deletes, or changes the status of existing videos.
- Users see a paginated list of their own videos: title, URL, site, status, scheduled and completed times, size, and
  duration. Admins see **all** videos. There is no search, sorting, or thumbnails on the fallback.
- Download progress (`downloadedBytes`) is **not** synced.
- A few minutes of sync lag in normal operation is acceptable.
- Running cost must be as close to zero as possible.
- Main → fallback is event-driven and goes through SQS. A periodic reconcile compares hashes to repair drift.
- The reconcile reads the fallback's DynamoDB table directly, with read-only access.
- A user's role is captured when they sign up to the fallback and is not refreshed afterwards (accepted staleness).

### Out of scope

Role sync, thumbnails, search/sort, progress, error details, deletes or edits made on the fallback, and admins seeing
other users' pending requests.

## Architecture

```
 Main side (home server)                 AWS (fallback-api SAM stack)
 ───────────────────────                 ────────────────────────────
 api
  ├─ FallbackSyncPublisher ──────────▶ [main-to-fallback SQS + DLQ] ──▶ SyncFunction (Lambda)
  │    ▲ internal topics:                                                    │ writes
  │    │ scheduled-video-downloads,                                          ▼
  │    │ fallback-sync-requests                                  DynamoDB table (+ GSI1)
  ├─ FallbackReconciler ── read-only Scan ──────────────────────────────────▶ ▲
  │                                                                          │ writes
  └─ FallbackRequestConsumer ◀── poll ── [fallback-to-main SQS + DLQ] ◀── ApiFunction (FastAPI)
       → ApiSchedulingService.schedule                           POST /schedule, GET /schedule
```

### Fallback side (`fallback-api/`, all in `template.yaml`)

- **`ScheduledVideosTable`**: DynamoDB table, `PAY_PER_REQUEST`, TTL on attribute `ttl`, sparse GSI `GSI1`.
  No point-in-time recovery.
- **`MainToFallbackQueue` + DLQ**: standard queue, 4-day retention (so a dead-lettered message still gets 10 of
  the DLQ's 14 days), visibility 180 s, `maxReceiveCount` 5.
- **`FallbackToMainQueue` + DLQ**: standard queue, 14-day retention, visibility 300 s, `maxReceiveCount` 5.
- **`SyncFunction`**: new Lambda (`handler.sync_handler`). SQS event source on `MainToFallbackQueue`, batch size 10,
  `ReportBatchItemFailures`.
- **`ApiFunction`**: the existing Lambda; gains `POST /schedule` and `GET /schedule`.
- **`MainSideSyncUser`**: IAM user and policy allowing
  - `sqs:SendMessage` on `MainToFallbackQueue`
  - `sqs:ReceiveMessage`, `sqs:DeleteMessage`, `sqs:ChangeMessageVisibility` on `FallbackToMainQueue`
  - `dynamodb:Scan` on the table's `GSI1` index (the reconcile scans the index, not the table)

  Access keys are created by hand, never output by the stack.
- **DLQ alarms**: one CloudWatch alarm per DLQ (`ApproximateNumberOfMessagesVisible > 0`) → SNS topic → email.
- **Log groups**: explicit log groups for both Lambdas, with 14-day retention.
- **Cognito**: new custom attribute `role` (mutable). `UserPoolClient` gets explicit `WriteAttributes` limited to
  `email`, `given_name` and `family_name`, so users cannot write `custom:role`.

### Main side (`api` module, package `com.ruchij.api.services.fallback`)

The whole feature is off unless `FALLBACK_SYNC_ENABLED=true`.

- **`FallbackSyncPublisher`**
  - Subscribes to `scheduled-video-downloads` (group id `fallback-sync`) and to a new internal topic,
    `fallback-sync-requests`, whose message is `FallbackSyncRequest(videoId: String)`. It gets a `MessagingTopic`
    instance with Avro and JSON codecs, like every other topic.
  - Collects video ids in 30 s windows and removes duplicates. It then reads each video's current row and
    `permission` user ids, and sends a `ScheduledVideoUpsert` for each row found and a `ScheduledVideoRemoval` for
    each id with no row.
  - A `Deleted` event (an admin delete, published before batch hard-deletes the row) becomes a removal unless the
    row read at send time was scheduled after the event's timestamp, i.e. the URL was scheduled again. A replayed
    `Deleted` event therefore can't tombstone a live video. The latest `Deleted` event for an id in the window
    counts even when later events for it follow, so an update of the row awaiting its hard delete (e.g. an admin
    changing its status) can't turn the removal into an upsert of a row about to disappear.
  - Sends with `SendMessageBatch`, at most 10 messages and 240 KiB of bodies per call, a margin under the 256 KiB
    limit. A message SQS rejects as the sender's fault, for its entry or for a whole call (e.g.
    `BatchRequestTooLong`, after which each message of the call is sent alone), is logged and dropped.
- **New `FallbackSyncRequest` publishes** in `ApiSchedulingServiceImpl`, at the two write paths that publish nothing
  today:
  - `schedule` of a URL that already exists, which adds a permission row
  - non-admin `deleteById`, which removes a permission row

  These use a separate topic, because batch's `SchedulerImpl` reacts to `scheduled-video-downloads`.
- **`FallbackReconciler`**: see Flow D.
- **`FallbackRequestConsumer`**: see Flow B.
- **Leader election:** the reconcile and the consumer each run on one API instance, which holds a lease key with a
  TTL in the Redis `KeyValueStore`. If the store has no set-if-absent operation, add one.
- **Dependencies:** AWS SDK v2 `sqs` and `dynamodb`. Record them in `project/Dependencies.scala` and update the
  README tables, per `CLAUDE.md`.
- **Configuration:** `application.conf` gains a `fallback-sync` block, each setting with a `${?VAR}` override:
  `FALLBACK_SYNC_ENABLED`, `FALLBACK_SYNC_MAIN_TO_FALLBACK_QUEUE_URL`,
  `FALLBACK_SYNC_FALLBACK_TO_MAIN_QUEUE_URL`, `FALLBACK_SYNC_TABLE_NAME`, `FALLBACK_SYNC_AWS_REGION`.
  Credentials come from the default AWS provider chain.

## Messages

All messages are JSON with a `type` discriminator. Timestamps are fixed-width ISO-8601 UTC with exactly six
fractional digits (`2026-09-26T08:15:30.123456Z`), so the fallback can compare them as strings; any other shape is
rejected.

### main → fallback (`MainToFallbackQueue`)

- **`ScheduledVideoUpsert`**: `videoId`, `capturedAt`, `hash`, `userIds[]`, `url`, `videoSite`, `title`,
  `durationMs`, `sizeBytes`, `status`, `scheduledAt`, `completedAt?`
- **`ScheduledVideoRemoval`**: `videoId`, `capturedAt`
- **`RequestResolved`**: `requestId`, `userId`, `outcome`, where `outcome` is one of
  - `{"result": "Scheduled", "upsert": <ScheduledVideoUpsert>}`
  - `{"result": "Rejected", "reason": "..."}`

- `capturedAt` is the database's `CURRENT_TIMESTAMP`, read in the same transaction as the row it versions (in
  Postgres, the transaction's start, so never after the read). Using the DB clock keeps every API instance on one
  clock. It is the version used for ordering. `lastUpdatedAt` can't be used, because permission changes don't
  update it.
- `status` is the main side's `SchedulingStatus` name, passed through unchanged.

### fallback → main (`FallbackToMainQueue`)

| type | fields |
|---|---|
| `ScheduleRequest` | `requestId` (UUID), `userId`, `url`, `requestedAt` |

### Hash

The main side computes the hash, and **only** the main side. It is SHA-256 over a canonical encoding of `videoId`,
`url`, `videoSite`, `title`, `durationMs`, `sizeBytes`, `status`, `scheduledAt`, `completedAt`, and the sorted
`userIds`, truncated to the first 16 hex characters.

- It excludes `downloadedBytes`, `lastUpdatedAt` and `capturedAt`.
- The fallback stores the hash as an opaque string and never recomputes it. That avoids mismatches caused by Scala
  and Python encoding the same data differently.

### Contract fixtures

`fallback-api/contract/` holds one JSON example for each message type. The Scala tests decode and round-trip every
example. The Python tests parse every example and build messages that match their shape. Any schema change must
update these fixtures, which fails the other side's tests until both sides agree.

## DynamoDB schema

A single table with string keys `PK` and `SK`:

| Item | PK | SK |
|---|---|---|
| Video | `VIDEO#<videoId>` | `VIDEO` |
| User link | `USER#<userId>` | `VIDEO#<scheduledAt>#<videoId>` |
| Pending | `USER#<userId>` | `PENDING#<requestId>` |

- **Video** attributes: `userIds` (a list), `capturedAt`, `hash`, `deleted`, and the display fields. Live videos
  also have `GSI1PK = "VIDEO"` and `GSI1SK = <scheduledAt>#<videoId>`. `fallback-api/contract/dynamodb-video-item.json`
  is the exact item stored for the upsert fixture.
- **Large applies:** a change needing more than 100 writes locks the video item (`lockId`, `lockedUntil`, 2 min, and
  `pendingLinkKeys`), writes the links in batches, then puts the video item and drops the lock. A new video's lock
  sits on a placeholder marked `deleted`, which listings and the reconcile ignore.
- **User link** keys put `scheduledAt` in the sort key so a user's list comes back newest first, like the admin list.
  A video's `scheduledAt` never changes, so the key of a link to delete can be built from the stored video item.
- **User link** attributes: a copy of the display fields, which are `url`, `videoSite`, `title`, `durationMs`,
  `sizeBytes`, `status`, `scheduledAt` and `completedAt`.
- **Pending** attributes: `url`, `requestedAt`, `status` (`Pending` or `Rejected`), `reason?`, `ttl?`.

- `GSI1` is sparse, with keys `GSI1PK` and `GSI1SK` and projection `ALL`. It contains exactly the live videos, which
  is what the admin list reads.
- `ttl` is set on:
  - rejected pending items, to 7 days after rejection
  - tombstoned video items, to 15 days after removal (longer than the 14-day queue retention)

## Flows

### A. Scheduling on the fallback

1. `POST /schedule {"url": "..."}` with a Cognito bearer token. The URL must be an absolute `http(s)` URL; otherwise
   respond 400. `userId` comes from `custom:user_id`, and `requestId` is a new UUID.
2. Send the `ScheduleRequest` to `FallbackToMainQueue`. If the send fails, respond 503 and write nothing.
3. Put `PENDING#<requestId>` with status `Pending`, then respond `202 {"requestId": "..."}`.

The send comes first on purpose. If the DynamoDB write fails after it, the request is still processed, and its
`RequestResolved` finds no pending item, which is harmless. The reverse order could leave a pending item that no
request will ever resolve.

### B. The main side consuming requests (`FallbackRequestConsumer`)

1. The lease holder polls every 60 s (`ReceiveMessage`, one message at a time, so a slow schedule can't push a batch
   past the visibility timeout) and drains the queue while messages keep arriving.
2. For each `ScheduleRequest`, call `ApiSchedulingService.schedule(url, userId)`, with the URL's fragment dropped as
   the main API's own route does. URLs over 2048 characters are rejected.
   The consumer is idempotent: each reply is stored in Redis for 14 days, keyed by `requestId`, before it is sent,
   and a redelivered request only re-sends its stored reply, so it can't re-schedule a video deleted since.
3. **Success:** read the video's current state, send `RequestResolved{Scheduled, upsert}`, then delete the message.
4. **Permanent failure** (invalid or unsupported URL, unknown user): send `RequestResolved{Rejected, reason}`, then
   delete the message. An unknown user gets a generic reason, so replies don't reveal which user ids exist.
5. **Transient failure** (DB unavailable, metadata fetch timeout): leave the message. It becomes visible again after
   300 s, and after 5 receives it moves to the DLQ.

### C. Main changes reaching DynamoDB

1. `FallbackSyncPublisher` sends upserts and removals as described above.
2. `SyncFunction` applies each message in one `TransactWriteItems` call:
   - **Guard.** Read the video item. If the stored `capturedAt` is equal to or newer than the incoming one, skip the
     message and report it as a success.
   - **Upsert.** Compare the old and new `userIds`. Put a link for each current user, delete the links of removed
     users, and put the video item with its `GSI1` attributes. An empty `userIds` removes every link but keeps the
     video item.
   - **Removal, or an upsert with status `Deleted`.** Delete all links and write the video item as a tombstone:
     `deleted = true`, no `GSI1` attributes, `ttl` = 15 days. Treating `Deleted` as a removal covers the case where
     batch hasn't hard-deleted the row yet.
   - **`RequestResolved`.**
     - `Scheduled`: apply the embedded upsert (with the guard) and delete `PENDING#<requestId>`, in the same
       transaction.
     - `Rejected`: update the pending item to `Rejected`, with its reason and a 7-day `ttl`.
3. Failures are reported per message through partial batch responses. After 5 receives a message moves to the DLQ.

### D. Reconcile (`FallbackReconciler`)

Runs daily, when the API starts (which also does the initial backfill), and when the "reconcile needed" flag is set.
The time of each completed reconcile is kept in Redis, and the daily run is skipped when any instance completed one in
the last 20 h. A startup or flagged run that finds the lock held flags a retry, since the holder may have crashed.

1. Acquire the lease.
2. **Scan the manifest first.** Scan the sparse `GSI1` (projection `ALL`, so it holds exactly the live videos) with
   `ProjectionExpression` `PK, hash, capturedAt`, paginating until the scan completes. An item with a missing or
   unparseable hash or `capturedAt` stays in the manifest under a hash that never matches, so it is always repaired.
3. **Then read the DB:** every scheduled video with its `permission` user ids, computing each hash.
4. **Diff:**
   - in the DB but not in the manifest → upsert
   - in both, with a different hash → upsert
   - in the manifest but not in the DB → removal
5. **Mass-removal guard:** if the DB returned no videos while the manifest has some, or removals exceed 50 or 20% of
   the manifest (whichever is more), log an error and send only the upserts, unless
   `FALLBACK_SYNC_RECONCILE_ALLOW_MASS_REMOVAL` is set.
6. Send the fixes to `MainToFallbackQueue`, clear the flag, and release the lease.

The order matters. A video synced between the two reads appears in the DB read and is harmlessly upserted again.
Reading the DB first would show such a video as manifest-only, and it would be wrongly removed. Removals carry
`capturedAt`, so if a video is removed and later scheduled again, the newer upsert still wins.

### E. Listing on the fallback

`GET /schedule?status=<SchedulingStatus>&pageToken=<token>` returns
`{"videos": [...], "pending": [...], "nextPageToken": "..."}`.

- **Users:** two queries on `PK = USER#<userId>`:
  - `begins_with(SK, "VIDEO#")`, newest first, paginated, with the status filter as a `FilterExpression`, for `videos`.
    A filtered page can hold fewer items than the page size; clients follow `nextPageToken` until it is absent.
  - `begins_with(SK, "PENDING#")`, unpaginated because the set is small, for `pending`.
- **Admins** (`custom:role = Admin`): query `GSI1` with `GSI1PK = "VIDEO"`, newest first, for `videos`. `pending`
  comes from the admin's own user partition.
- `pageToken` is the base64 of `LastEvaluatedKey`.

## Fallback-side changes to existing code

The following must be fixed or completed first:

- **`get_authentication_service()` returns nothing** (`pass`), and `authentication_router` is not mounted in
  `src/main.py`. Implement the first and mount the second.
- **`CognitoUserService.create_user` calls `sign_up` without a `SecretHash`.** The client is created with
  `GenerateSecret: true`, so Cognito rejects the call. Reuse the `_secret_hash` logic from
  `CognitoAuthenticationService`.
- **`VideoDownloaderUserValidationService` ignores the role.** Read `role` from the main API's logout response, add
  `role` to `User`, write it at sign-up as `custom:role`, and read it back in `authenticate`.

## Error handling

| Where | Failure | Handling |
|---|---|---|
| `POST /schedule` | SQS send fails | 503, nothing written |
| `SyncFunction` | One message fails | Partial batch failure; DLQ after 5 receives; alarm |
| `FallbackSyncPublisher` | SQS unreachable | 3 retries with backoff, then set the "reconcile needed" flag |
| Sync request publish | Kafka publish fails or times out | Set the flag; skip for 60 s once an id's publish times out |
| SQS send | Entry rejected as the sender's fault | Log an error and drop it; retry other failures, then raise |
| `FallbackRequestConsumer` | Permanent or transient failure | Flow B steps 4 and 5; DLQ after 5 receives; alarm |
| `FallbackReconciler` | Scan or send fails | Leave the flag set, log, and retry on the next trigger |

The "reconcile needed" flag lives in Redis. Sync never blocks main-side request handling or DB writes.

## Cost profile

- **SQS:** event traffic, plus one consumer polling every 60 s (about 45k requests a month), plus the idle polling of
  the SQS trigger on `SyncFunction`. This is expected to stay inside the 1M free requests a month.
- **DynamoDB (on-demand):** writes happen only for real changes. A reconcile with no drift costs one scan (about 1k
  read units for a few MB of table) and no writes.
- **Lambda and API Gateway:** fallback traffic plus sync batches, inside Lambda's free tier.
- **CloudWatch:** 2 alarms (inside the 10 free), and logs kept 14 days.

## Testing

- **Python (pytest + moto, DynamoDB, SQS, Cognito):**
  - `SyncFunction`:
    - the guard, including equal and older `capturedAt`
    - adding and removing links, and empty `userIds`
    - removal, and `Deleted` status treated as removal
    - tombstone TTL, and a late upsert arriving after a tombstone
    - `RequestResolved` in both outcomes
    - partial batch failures
  - Routes:
    - `POST /schedule`: success, invalid URL, SQS failure
    - `GET /schedule`: user and admin, status filter, pagination, pending items
  - Cognito: sign-up with `SecretHash`, writing `custom:role` and reading it back
  - Contract fixtures
- **Scala (ScalaTest):**
  - Pure tests:
    - hash stability, including the order of `userIds`
    - the reconcile diff
    - the rule that the manifest is read before the DB
  - `FallbackSyncPublisher`: windowing, removal of duplicates, a missing row becoming a removal, all using the
    in-memory `Fs2PubSub`
  - `FallbackRequestConsumer`: classifying each failure as permanent or transient, with a stub scheduling service
  - SQS and DynamoDB integration against LocalStack, added as another option in the existing `containers` resources
    provider
  - Contract fixtures

## Delivery

One spec, implemented in two phases:

1. **Fallback side.** Table and index, queues and DLQs, alarms, `SyncFunction`, the routes, the Cognito role, and the
   fixes to existing code. It can be deployed on its own; nothing flows until phase 2.
2. **Main side.** Configuration, dependencies, `FallbackSyncPublisher`, the new publishes, `FallbackRequestConsumer`,
   and `FallbackReconciler`.

## Risks and open questions

- **Changes to the Cognito schema** (adding `custom:role`) on the existing user pools must be checked with a
  CloudFormation change set. If CloudFormation requires replacing the pool, existing fallback users would have to
  sign up again.
- **Consumer-group behaviour for the Redis and Doobie pub/sub backends** must deliver each `scheduled-video-downloads`
  message to one `fallback-sync` consumer. Duplicates would be harmless because of the guard, but they would cost
  extra SQS sends.
- **Stale role:** if a user's role changes on the main side, their fallback role stays as it was at sign-up. This
  was accepted for now.
