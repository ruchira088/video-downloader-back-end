# Fallback Sync, Phase 2 (Main Side) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Scala `api` module keep the fallback's DynamoDB copy in sync — push scheduled-video changes to
`main-to-fallback`, process schedule requests from `fallback-to-main`, and reconcile drift by comparing hashes — all
off unless `FALLBACK_SYNC_ENABLED=true`.

**Architecture:** A new `com.ruchij.api.services.fallback` package. Pure pieces (messages, hash, diff) sit behind
small traits for the AWS edges (`FallbackSyncTransport`, `FallbackRequestQueue`, `FallbackManifestReader`) and the
DB (`FallbackSyncDao`), so the logic is tested with stubs and the AWS implementations with ElasticMQ and DynamoDB
Local containers. `ApiApp` starts the combined stream in the background, like `BackgroundServiceImpl`.

**Tech Stack:** Scala 2.13, cats-effect 3, fs2, doobie, circe, pureconfig, AWS SDK for Java v2 (`sqs`, `dynamodb`
2.55.6), ScalaTest, TestContainers (`GenericContainer`).

**Spec:** `docs/superpowers/specs/2026-09-26-fallback-sync-design.md`
**Depends on:** phase 1 (`docs/superpowers/plans/2026-09-26-fallback-sync-phase-1-fallback-side.md`) — the
`fallback-api/contract/*.json` fixtures and the DynamoDB item layout.

## Global Constraints

- The build uses `-Xfatal-warnings`: no unused imports, no deprecated APIs (use
  `DefaultCredentialsProvider.builder().build()`, `messageSystemAttributeNames(...)`), exhaustive matches.
- Scala stays on 2.13 (never Scala 3).
- Tagless final: components are parameterised over `F[_]` (and `T[_]` for DB work lifted through
  `implicit transaction: T ~> F`); `IO` only appears in tests and `*App` objects.
- Time comes from `com.ruchij.core.types.Clock` (`Clock[F].timestamp`), not cats `Clock`.
- Logging uses `com.ruchij.core.logging.Logger` (`logger.warn[F](...)`, `logger.error[F](message, throwable)`).
- Sync message JSON is camelCase with a `type` discriminator; timestamps are formatted exactly
  `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` in UTC; `None` fields are omitted.
- The hash is computed only here: SHA-256 over a canonical encoding of `videoId`, `url`, `videoSite`, `title`,
  `durationMs`, `sizeBytes`, `status`, `scheduledAt`, `completedAt` and the sorted `userIds`, first 16 hex chars.
- DynamoDB manifest read: `Scan` with `ProjectionExpression "PK, #hash, capturedAt"` and
  `FilterExpression "SK = :video AND (attribute_not_exists(deleted) OR deleted = :false)"`, `PK` stripped of `VIDEO#`.
- Reconcile reads the manifest **before** the database.
- New dependency versions go in `project/Dependencies.scala` and the README Technology Stack table (`CLAUDE.md`).
- Markdown lines ≤ 120 characters.
- Under sbt 2, `sbt test` skips unchanged suites; use `sbt "api/testOnly ..."` while iterating and `sbt testFull`
  for the final run (the contract fixtures live outside the module, so incremental caching cannot see them change).
- Commit messages follow the repo style and end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

### Deviations from the spec (decided while planning)

- **No lease for the request consumer.** Every API instance polls `fallback-to-main` every 60 s with 20 s long
  polling; SQS visibility timeouts already stop two instances handling the same message. Three instances cost about
  130k requests a month, inside the free tier, and this removes lease renewal logic.
- **The reconcile lock is best-effort and uses the existing `KeyValueStore` API** (get, put-with-TTL, get again to
  confirm ownership) instead of adding a set-if-absent operation to three store implementations. Two instances
  reconciling at once is harmless because every message is idempotent.
- **A reconcile re-checks each "remove" candidate in the database before sending a removal.** Paging through
  `SchedulingDao.search` while rows are deleted can skip a row; the re-check stops that from removing a live video.
- **A transient schedule failure on its final delivery (`ApproximateReceiveCount >= 5`) is answered with
  `RequestResolved{Rejected}`** and the message deleted, so the user sees the failure instead of a request that
  silently dead-letters. The DLQ then only catches undecodable messages or failures to send the reply.
- **Unknown users are checked with `UserDao.findById` before scheduling**, because `schedule` itself surfaces an
  unknown user as a raw foreign-key `SQLException` after running metadata analysis.
- **Integration tests use ElasticMQ and DynamoDB Local** (`GenericContainer`) instead of LocalStack, whose images
  now need an auth token; no new TestContainers module is required.
- **When sync is disabled, `fallback-sync-requests` gets a no-op publisher**, so enabling nothing never publishes to
  (or needs) a new Kafka topic.

## Review Focus

1. **A video deleted between the manifest scan and the DB read, or skipped by pagination,** must not be removed
   from the fallback while it still exists — the removal re-check covers it. Test in Task 6.
2. **SQS rejecting part of a `SendMessageBatch`** (per-entry failure with an overall 200) must be treated as a
   failure and retried, not silently dropped. Test in Task 4.
3. **The fallback being unreachable for longer than the retries** must set the "reconcile needed" flag and keep the
   stream alive (and still commit), not crash the API's background fiber. Test in Task 5.
4. **A schedule request for a URL that `Uri.fromString` rejects, or for a user who no longer exists,** must produce
   `Rejected` with a reason and delete the message, not retry five times. Test in Task 7.
5. **Timestamps with zero milliseconds** (`Instant.toString` prints `...:00Z` without `.000`) must still be written
   as `.000Z`; otherwise the fallback's string comparison of `capturedAt` breaks. Test in Task 2.

---

## File Structure

All new main-code files live under `api/src/main/scala/com/ruchij/api/`; tests under `api/src/test/scala/...`.

| File | Responsibility |
|---|---|
| `config/FallbackSyncConfiguration.scala` | Config case class, `Disabled`, validation into `FallbackSyncSettings` |
| `services/fallback/models/SyncMessages.scala` | Message ADTs |
| `services/fallback/models/SyncJson.scala` | Timestamp format, encoders, `ScheduleRequest` decoder |
| `services/fallback/models/FallbackSyncRequest.scala` | Internal topic message + `MessagingTopic` instance |
| `services/fallback/SyncHash.scala` | Canonical hash |
| `services/fallback/ScheduledVideoUpserts.scala` | `SyncedVideo` → `ScheduledVideoUpsert` |
| `services/fallback/FallbackSyncDao.scala` | Narrow DB read trait + Doobie implementation |
| `services/fallback/NoOpPublisher.scala` | Publisher used when sync is disabled |
| `services/fallback/aws/FallbackSyncAwsClients.scala` | SQS and DynamoDB async clients as `Resource`s |
| `services/fallback/aws/FallbackSyncTransport.scala` | Send messages to `main-to-fallback` |
| `services/fallback/aws/FallbackRequestQueue.scala` | Receive / delete from `fallback-to-main` |
| `services/fallback/aws/FallbackManifestReader.scala` | Scan the DynamoDB manifest |
| `services/fallback/FallbackSyncCoordination.scala` | Reconcile flag + best-effort lock over `KeyValueStore` |
| `services/fallback/FallbackSyncPublisher.scala` | 30 s windows of video ids → messages → SQS |
| `services/fallback/ReconcileDiff.scala` | Pure diff of manifest vs DB |
| `services/fallback/FallbackReconciler.scala` | Reconcile run + schedule |
| `services/fallback/FallbackRequestConsumer.scala` | Poll requests, schedule, reply |
| `services/fallback/FallbackSync.scala` | Wires the above into one stream |
| `ApiApp.scala`, `models/ApiMessageBrokers.scala`, `config/ApiServiceConfiguration.scala` | Wiring |
| `services/scheduling/ApiSchedulingServiceImpl.scala` | Publishes `FallbackSyncRequest` at the two silent paths |

---

### Task 1: Configuration and dependencies

**Files:**
- Create: `api/src/main/scala/com/ruchij/api/config/FallbackSyncConfiguration.scala`
- Modify: `api/src/main/scala/com/ruchij/api/config/ApiServiceConfiguration.scala`
- Modify: `api/src/main/resources/application.conf`
- Modify: `project/Dependencies.scala`, `build.sbt`, `README.md`
- Modify: `development/src/main/scala/com/ruchij/development/DevelopmentApp.scala:72`,
  `api/src/test/scala/com/ruchij/api/ApiAppSpec.scala:41`,
  `api/src/test/scala/com/ruchij/api/config/ApiServiceConfigurationSpec.scala:94`
- Create: `api/src/test/scala/com/ruchij/api/config/FallbackSyncConfigurationSpec.scala`

**Interfaces:**
- Produces:
  - `FallbackSyncConfiguration(enabled: Boolean, mainToFallbackQueueUrl: Option[String],
    fallbackToMainQueueUrl: Option[String], tableName: Option[String], awsRegion: Option[String],
    awsEndpointUrl: Option[String])` with `settings: Either[IllegalArgumentException, Option[FallbackSyncSettings]]`
    and companion `val Disabled`
  - `FallbackSyncSettings(mainToFallbackQueueUrl: String, fallbackToMainQueueUrl: String, tableName: String,
    awsRegion: String, awsEndpointUrl: Option[String])`
  - `ApiServiceConfiguration` gains a last field `fallbackSyncConfiguration: FallbackSyncConfiguration`
  - `Dependencies.awsSqs`, `Dependencies.awsDynamoDb`

- [ ] **Step 1: Write the failing test**

`api/src/test/scala/com/ruchij/api/config/FallbackSyncConfigurationSpec.scala`:

```scala
package com.ruchij.api.config

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import pureconfig.ConfigSource
import pureconfig.generic.auto._

class FallbackSyncConfigurationSpec extends AnyFlatSpec with Matchers {

  "FallbackSyncConfiguration" should "parse a fully configured block into settings" in {
    val configuration =
      ConfigSource
        .string(
          """
            enabled = true
            main-to-fallback-queue-url = "https://sqs.example.com/m2f"
            fallback-to-main-queue-url = "https://sqs.example.com/f2m"
            table-name = "prod-fallback-scheduled-videos"
            aws-region = "ap-southeast-2"
          """
        )
        .loadOrThrow[FallbackSyncConfiguration]

    configuration.settings mustBe Right(
      Some(
        FallbackSyncSettings(
          "https://sqs.example.com/m2f",
          "https://sqs.example.com/f2m",
          "prod-fallback-scheduled-videos",
          "ap-southeast-2",
          None
        )
      )
    )
  }

  it should "produce no settings when disabled, even with nothing else set" in {
    ConfigSource.string("enabled = false").loadOrThrow[FallbackSyncConfiguration].settings mustBe Right(None)
  }

  it should "fail when enabled without every required setting" in {
    FallbackSyncConfiguration.Disabled.copy(enabled = true, tableName = Some("t")).settings.isLeft mustBe true
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `sbt "api/testOnly com.ruchij.api.config.FallbackSyncConfigurationSpec"`
Expected: compilation FAIL — `not found: type FallbackSyncConfiguration`.

- [ ] **Step 3: Implement**

`api/src/main/scala/com/ruchij/api/config/FallbackSyncConfiguration.scala`:

```scala
package com.ruchij.api.config

final case class FallbackSyncSettings(
  mainToFallbackQueueUrl: String,
  fallbackToMainQueueUrl: String,
  tableName: String,
  awsRegion: String,
  awsEndpointUrl: Option[String]
)

final case class FallbackSyncConfiguration(
  enabled: Boolean,
  mainToFallbackQueueUrl: Option[String],
  fallbackToMainQueueUrl: Option[String],
  tableName: Option[String],
  awsRegion: Option[String],
  awsEndpointUrl: Option[String]
) {
  val settings: Either[IllegalArgumentException, Option[FallbackSyncSettings]] =
    if (!enabled) Right(None)
    else
      (mainToFallbackQueueUrl, fallbackToMainQueueUrl, tableName, awsRegion) match {
        case (Some(mainToFallback), Some(fallbackToMain), Some(table), Some(region)) =>
          Right(Some(FallbackSyncSettings(mainToFallback, fallbackToMain, table, region, awsEndpointUrl)))

        case _ =>
          Left {
            new IllegalArgumentException(
              "FALLBACK_SYNC_ENABLED is true, so FALLBACK_SYNC_MAIN_TO_FALLBACK_QUEUE_URL, " +
                "FALLBACK_SYNC_FALLBACK_TO_MAIN_QUEUE_URL, FALLBACK_SYNC_TABLE_NAME and FALLBACK_SYNC_AWS_REGION " +
                "must all be set"
            )
          }
      }
}

object FallbackSyncConfiguration {
  val Disabled: FallbackSyncConfiguration = FallbackSyncConfiguration(false, None, None, None, None, None)
}
```

Add `fallbackSyncConfiguration: FallbackSyncConfiguration` as the **last** field of `ApiServiceConfiguration`, then
pass `FallbackSyncConfiguration.Disabled` as the new last argument at the three construction sites
(`DevelopmentApp.scala:72`, `ApiAppSpec.scala:41`, `ApiServiceConfigurationSpec.scala:94`), adding the import in each.
If `ApiServiceConfigurationSpec` parses a config string into the expected value, it now also parses the defaults
below — `Disabled` matches them.

Append to `api/src/main/resources/application.conf`:

```hocon

fallback-sync-configuration {
  enabled = false
  enabled = ${?FALLBACK_SYNC_ENABLED}

  main-to-fallback-queue-url = ${?FALLBACK_SYNC_MAIN_TO_FALLBACK_QUEUE_URL}
  fallback-to-main-queue-url = ${?FALLBACK_SYNC_FALLBACK_TO_MAIN_QUEUE_URL}
  table-name = ${?FALLBACK_SYNC_TABLE_NAME}
  aws-region = ${?FALLBACK_SYNC_AWS_REGION}
  aws-endpoint-url = ${?FALLBACK_SYNC_AWS_ENDPOINT_URL}
}
```

In `project/Dependencies.scala`, next to the other versioned groups:

```scala
  private val AwsSdkVersion = "2.55.6"

  lazy val awsSqs = "software.amazon.awssdk" % "sqs" % AwsSdkVersion

  lazy val awsDynamoDb = "software.amazon.awssdk" % "dynamodb" % AwsSdkVersion
```

In `build.sbt`, add them to the `api` module's main dependencies:

```scala
      libraryDependencies ++=
        Seq(http4sEmberServer, postgresql, pureconfig, jbcrypt, logbackClassic, awsSqs, awsDynamoDb) ++ circe ++
          Seq(circeLiteral, pegdown).map(_ % Test)
```

In `README.md`, add a row to the Technology Stack table after `Configuration`:

```markdown
| AWS | AWS SDK for Java 2.55.6 (SQS, DynamoDB — fallback sync) |
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `sbt "api/testOnly com.ruchij.api.config.*"` and `sbt compile`
Expected: PASS, and every module compiles (DevelopmentApp included).

- [ ] **Step 5: Commit**

```bash
git add project/Dependencies.scala build.sbt README.md api development
git commit -m "Add fallback sync configuration and the AWS SDK SQS and DynamoDB dependencies.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Sync messages, JSON, hash and the contract tests

**Files:**
- Create: `api/src/main/scala/com/ruchij/api/services/fallback/models/SyncMessages.scala`
- Create: `api/src/main/scala/com/ruchij/api/services/fallback/models/SyncJson.scala`
- Create: `api/src/main/scala/com/ruchij/api/services/fallback/SyncHash.scala`
- Create: `api/src/main/scala/com/ruchij/api/services/fallback/FallbackSyncDao.scala` (only `SyncedVideo` in this
  task)
- Create: `api/src/main/scala/com/ruchij/api/services/fallback/ScheduledVideoUpserts.scala`
- Create: `api/src/test/scala/com/ruchij/api/services/fallback/ContractFixtures.scala`
- Create: `api/src/test/scala/com/ruchij/api/services/fallback/SyncJsonSpec.scala`
- Create: `api/src/test/scala/com/ruchij/api/services/fallback/SyncHashSpec.scala`
- Create: `api/src/test/scala/com/ruchij/api/services/fallback/FallbackSyncTestData.scala`

**Interfaces:**
- Produces (package `com.ruchij.api.services.fallback.models`):
  - `sealed trait MainToFallbackMessage`; `ScheduledVideoUpsert(videoId: String, capturedAt: Instant, hash: String,
    userIds: List[String], url: String, videoSite: String, title: String, durationMs: Long, sizeBytes: Long,
    status: String, scheduledAt: Instant, completedAt: Option[Instant])`; `ScheduledVideoRemoval(videoId: String,
    capturedAt: Instant)`; `RequestResolved(requestId: String, userId: String, outcome: ResolutionOutcome)`
  - `sealed trait ResolutionOutcome` with `ResolutionOutcome.Scheduled(upsert)` and `ResolutionOutcome.Rejected(reason)`
  - `ScheduleRequest(requestId: String, userId: String, url: String, requestedAt: Instant)`
  - `SyncJson.formatTimestamp(Instant): String`, `SyncJson.encode(MainToFallbackMessage): String`,
    `SyncJson.decodeScheduleRequest(String): Either[io.circe.Error, ScheduleRequest]`,
    implicit `mainToFallbackMessageEncoder`, `scheduleRequestEncoder`, `scheduleRequestDecoder`
- Produces (package `com.ruchij.api.services.fallback`):
  - `SyncHash.of(upsert: ScheduledVideoUpsert): String`
  - `SyncedVideo(scheduledVideoDownload: ScheduledVideoDownload, userIds: List[String])`
  - `ScheduledVideoUpserts.from(syncedVideo: SyncedVideo, capturedAt: Instant): ScheduledVideoUpsert`

- [ ] **Step 1: Write the test helpers and failing tests**

`api/src/test/scala/com/ruchij/api/services/fallback/ContractFixtures.scala`:

```scala
package com.ruchij.api.services.fallback

import io.circe.{Json, Printer}
import io.circe.parser.parse

import java.nio.file.{Files, Path, Paths}

object ContractFixtures {
  // Forked tests run from the module directory and unforked ones from the repository root, so search upwards.
  private lazy val directory: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .map(_.resolve("fallback-api").resolve("contract"))
      .find(Files.isDirectory(_))
      .getOrElse(throw new IllegalStateException("fallback-api/contract not found"))

  def read(name: String): String = Files.readString(directory.resolve(name))

  def json(name: String): Json = parse(read(name)).fold(throw _, identity)

  def canonical(json: Json): String = Printer.noSpacesSortKeys.print(json)
}
```

`api/src/test/scala/com/ruchij/api/services/fallback/FallbackSyncTestData.scala`:

```scala
package com.ruchij.api.services.fallback

import com.ruchij.api.services.fallback.models.ScheduledVideoUpsert
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.scheduling.models.{ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.daos.videometadata.models.{CustomVideoSite, VideoMetadata}
import com.ruchij.core.types.TimeUtils
import org.http4s.MediaType
import org.http4s.implicits.http4sLiteralsSyntax

import java.time.Instant
import scala.concurrent.duration._

object FallbackSyncTestData {
  val capturedAt: Instant = TimeUtils.instantOf(2026, 9, 26, 8, 15)

  val fixtureUpsert: ScheduledVideoUpsert =
    ScheduledVideoUpsert(
      videoId = "youtube-1a2b3c4d5e6f",
      capturedAt = Instant.parse("2026-09-26T08:15:30.123Z"),
      hash = "0f1e2d3c4b5a6978",
      userIds = List("user-1", "user-2"),
      url = "https://www.youtube.com/watch?v=abc123",
      videoSite = "YouTube",
      title = "Sample video",
      durationMs = 212000,
      sizeBytes = 48234567,
      status = "Completed",
      scheduledAt = Instant.parse("2026-09-25T21:04:11.000Z"),
      completedAt = Some(Instant.parse("2026-09-25T21:09:42.500Z"))
    )

  def scheduledVideoDownload(videoId: String, status: SchedulingStatus = SchedulingStatus.Queued)
    : ScheduledVideoDownload = {
    val timestamp = TimeUtils.instantOf(2026, 9, 25, 21, 4)
    val thumbnail = FileResource(s"$videoId-thumbnail", timestamp, "/opt/thumbnail.jpg", MediaType.image.jpeg, 100)

    ScheduledVideoDownload(
      timestamp,
      timestamp,
      status,
      0,
      VideoMetadata(
        uri"https://example.com/video",
        videoId,
        CustomVideoSite.SpankBang,
        s"Title of $videoId",
        5.minutes,
        50000,
        thumbnail
      ),
      None,
      None
    )
  }
}
```

(If `TimeUtils.instantOf` takes a different arity, use `Instant.parse("2026-09-26T08:15:00.000Z")` instead — it is
used in `ApiSchedulingServiceImplSpec` with `(2024, 5, 15, 10, 30)`.)

`api/src/test/scala/com/ruchij/api/services/fallback/SyncJsonSpec.scala`:

```scala
package com.ruchij.api.services.fallback

import com.ruchij.api.services.fallback.ContractFixtures.{canonical, json, read}
import com.ruchij.api.services.fallback.FallbackSyncTestData.fixtureUpsert
import com.ruchij.api.services.fallback.models._
import io.circe.parser.parse
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.time.Instant

class SyncJsonSpec extends AnyFlatSpec with Matchers {

  private def encoded(message: MainToFallbackMessage): String =
    canonical(parse(SyncJson.encode(message)).fold(throw _, identity))

  "SyncJson" should "encode an upsert exactly like the contract fixture" in {
    encoded(fixtureUpsert) mustBe canonical(json("scheduled-video-upsert.json"))
  }

  it should "encode a removal exactly like the contract fixture" in {
    val removal = ScheduledVideoRemoval("youtube-1a2b3c4d5e6f", Instant.parse("2026-09-26T08:20:00Z"))

    encoded(removal) mustBe canonical(json("scheduled-video-removal.json"))
  }

  it should "encode both RequestResolved outcomes exactly like the contract fixtures" in {
    val scheduled =
      RequestResolved(
        "4d1c7f0e-8a57-4c1e-9b0b-2f6f3b6f9a10",
        "user-1",
        ResolutionOutcome.Scheduled(
          fixtureUpsert.copy(
            userIds = List("user-1"),
            status = "Queued",
            scheduledAt = Instant.parse("2026-09-26T08:15:29Z"),
            completedAt = None
          )
        )
      )
    val rejected =
      RequestResolved(
        "9a0e2b3c-1d4f-4e5a-8b6c-7d8e9f0a1b2c",
        "user-1",
        ResolutionOutcome.Rejected("Unsupported video site: example.com")
      )

    encoded(scheduled) mustBe canonical(json("request-resolved-scheduled.json"))
    encoded(rejected) mustBe canonical(json("request-resolved-rejected.json"))
  }

  it should "decode the schedule request fixture" in {
    SyncJson.decodeScheduleRequest(read("schedule-request.json")) mustBe Right(
      ScheduleRequest(
        "4d1c7f0e-8a57-4c1e-9b0b-2f6f3b6f9a10",
        "user-1",
        "https://www.youtube.com/watch?v=abc123",
        Instant.parse("2026-09-26T08:15:00Z")
      )
    )
  }

  it should "reject a message of another type" in {
    SyncJson.decodeScheduleRequest(read("scheduled-video-removal.json")).isLeft mustBe true
  }

  it should "always write milliseconds, even when they are zero" in {
    SyncJson.formatTimestamp(Instant.parse("2026-09-26T08:20:00Z")) mustBe "2026-09-26T08:20:00.000Z"
  }
}
```

`api/src/test/scala/com/ruchij/api/services/fallback/SyncHashSpec.scala`:

```scala
package com.ruchij.api.services.fallback

import com.ruchij.api.services.fallback.FallbackSyncTestData.{capturedAt, fixtureUpsert, scheduledVideoDownload}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.time.Instant

class SyncHashSpec extends AnyFlatSpec with Matchers {

  "SyncHash" should "be 16 lowercase hex characters" in {
    SyncHash.of(fixtureUpsert) must fullyMatch regex "[0-9a-f]{16}"
  }

  it should "ignore user order, capturedAt and the stored hash" in {
    val reordered =
      fixtureUpsert.copy(userIds = fixtureUpsert.userIds.reverse, capturedAt = Instant.EPOCH, hash = "ignored")

    SyncHash.of(reordered) mustBe SyncHash.of(fixtureUpsert)
  }

  it should "change when any synced field changes" in {
    val changes = List(
      fixtureUpsert.copy(title = "Other title"),
      fixtureUpsert.copy(status = "Queued"),
      fixtureUpsert.copy(userIds = List("user-1")),
      fixtureUpsert.copy(completedAt = None),
      fixtureUpsert.copy(sizeBytes = 1)
    )

    changes.map(SyncHash.of).distinct.size mustBe changes.size
    changes.map(SyncHash.of) must not contain SyncHash.of(fixtureUpsert)
  }

  "ScheduledVideoUpserts.from" should "map the DB model and fill in the hash" in {
    val upsert = ScheduledVideoUpserts.from(SyncedVideo(scheduledVideoDownload("video-1"), List("b", "a")), capturedAt)

    upsert.videoId mustBe "video-1"
    upsert.userIds mustBe List("a", "b")
    upsert.durationMs mustBe 300000
    upsert.status mustBe "Queued"
    upsert.url mustBe "https://example.com/video"
    upsert.hash mustBe SyncHash.of(upsert)
  }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `sbt "api/testOnly com.ruchij.api.services.fallback.*"`
Expected: compilation FAIL — `object models is not a member of package com.ruchij.api.services.fallback`.

- [ ] **Step 3: Implement**

`api/src/main/scala/com/ruchij/api/services/fallback/models/SyncMessages.scala`:

```scala
package com.ruchij.api.services.fallback.models

import java.time.Instant

sealed trait MainToFallbackMessage

final case class ScheduledVideoUpsert(
  videoId: String,
  capturedAt: Instant,
  hash: String,
  userIds: List[String],
  url: String,
  videoSite: String,
  title: String,
  durationMs: Long,
  sizeBytes: Long,
  status: String,
  scheduledAt: Instant,
  completedAt: Option[Instant]
) extends MainToFallbackMessage

final case class ScheduledVideoRemoval(videoId: String, capturedAt: Instant) extends MainToFallbackMessage

final case class RequestResolved(requestId: String, userId: String, outcome: ResolutionOutcome)
    extends MainToFallbackMessage

sealed trait ResolutionOutcome

object ResolutionOutcome {
  final case class Scheduled(upsert: ScheduledVideoUpsert) extends ResolutionOutcome

  final case class Rejected(reason: String) extends ResolutionOutcome
}

final case class ScheduleRequest(requestId: String, userId: String, url: String, requestedAt: Instant)
```

`api/src/main/scala/com/ruchij/api/services/fallback/models/SyncJson.scala`:

```scala
package com.ruchij.api.services.fallback.models

import io.circe.parser.decode
import io.circe.{Decoder, DecodingFailure, Encoder, Json}

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset}
import scala.util.Try

object SyncJson {
  private val TimestampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

  def formatTimestamp(instant: Instant): String = TimestampFormatter.format(instant)

  private def timestamp(instant: Instant): Json = Json.fromString(formatTimestamp(instant))

  private val timestampDecoder: Decoder[Instant] =
    Decoder.decodeString.emapTry(value => Try(Instant.parse(value)))

  private def upsertJson(upsert: ScheduledVideoUpsert): Json =
    Json
      .obj(
        "type" -> Json.fromString("ScheduledVideoUpsert"),
        "videoId" -> Json.fromString(upsert.videoId),
        "capturedAt" -> timestamp(upsert.capturedAt),
        "hash" -> Json.fromString(upsert.hash),
        "userIds" -> Json.arr(upsert.userIds.map(Json.fromString): _*),
        "url" -> Json.fromString(upsert.url),
        "videoSite" -> Json.fromString(upsert.videoSite),
        "title" -> Json.fromString(upsert.title),
        "durationMs" -> Json.fromLong(upsert.durationMs),
        "sizeBytes" -> Json.fromLong(upsert.sizeBytes),
        "status" -> Json.fromString(upsert.status),
        "scheduledAt" -> timestamp(upsert.scheduledAt),
        "completedAt" -> upsert.completedAt.fold(Json.Null)(timestamp)
      )
      .dropNullValues

  implicit val mainToFallbackMessageEncoder: Encoder[MainToFallbackMessage] =
    Encoder.instance {
      case upsert: ScheduledVideoUpsert => upsertJson(upsert)

      case ScheduledVideoRemoval(videoId, capturedAt) =>
        Json.obj(
          "type" -> Json.fromString("ScheduledVideoRemoval"),
          "videoId" -> Json.fromString(videoId),
          "capturedAt" -> timestamp(capturedAt)
        )

      case RequestResolved(requestId, userId, outcome) =>
        val outcomeJson =
          outcome match {
            case ResolutionOutcome.Scheduled(upsert) =>
              Json.obj("result" -> Json.fromString("Scheduled"), "upsert" -> upsertJson(upsert))

            case ResolutionOutcome.Rejected(reason) =>
              Json.obj("result" -> Json.fromString("Rejected"), "reason" -> Json.fromString(reason))
          }

        Json.obj(
          "type" -> Json.fromString("RequestResolved"),
          "requestId" -> Json.fromString(requestId),
          "userId" -> Json.fromString(userId),
          "outcome" -> outcomeJson
        )
    }

  implicit val scheduleRequestEncoder: Encoder[ScheduleRequest] =
    Encoder.instance { request =>
      Json.obj(
        "type" -> Json.fromString("ScheduleRequest"),
        "requestId" -> Json.fromString(request.requestId),
        "userId" -> Json.fromString(request.userId),
        "url" -> Json.fromString(request.url),
        "requestedAt" -> timestamp(request.requestedAt)
      )
    }

  implicit val scheduleRequestDecoder: Decoder[ScheduleRequest] =
    Decoder.instance { cursor =>
      for {
        messageType <- cursor.get[String]("type")
        _ <- Either.cond(
          messageType == "ScheduleRequest",
          (),
          DecodingFailure(s"Unexpected message type: $messageType", cursor.history)
        )
        requestId <- cursor.get[String]("requestId")
        userId <- cursor.get[String]("userId")
        url <- cursor.get[String]("url")
        requestedAt <- cursor.get[Instant]("requestedAt")(timestampDecoder)
      } yield ScheduleRequest(requestId, userId, url, requestedAt)
    }

  def encode(message: MainToFallbackMessage): String = mainToFallbackMessageEncoder(message).noSpaces

  def decodeScheduleRequest(body: String): Either[io.circe.Error, ScheduleRequest] =
    decode[ScheduleRequest](body)(scheduleRequestDecoder)
}
```

`api/src/main/scala/com/ruchij/api/services/fallback/SyncHash.scala`:

```scala
package com.ruchij.api.services.fallback

import com.ruchij.api.services.fallback.models.{ScheduledVideoUpsert, SyncJson}
import io.circe.Json

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object SyncHash {
  // Computed only on the main side; the fallback stores it as an opaque string and never recomputes it.
  def of(upsert: ScheduledVideoUpsert): String = {
    val canonical =
      Json
        .arr(
          Json.fromString(upsert.videoId),
          Json.fromString(upsert.url),
          Json.fromString(upsert.videoSite),
          Json.fromString(upsert.title),
          Json.fromLong(upsert.durationMs),
          Json.fromLong(upsert.sizeBytes),
          Json.fromString(upsert.status),
          Json.fromString(SyncJson.formatTimestamp(upsert.scheduledAt)),
          upsert.completedAt.fold(Json.Null)(instant => Json.fromString(SyncJson.formatTimestamp(instant))),
          Json.arr(upsert.userIds.sorted.map(Json.fromString): _*)
        )
        .noSpaces

    MessageDigest
      .getInstance("SHA-256")
      .digest(canonical.getBytes(StandardCharsets.UTF_8))
      .take(8)
      .map(byte => f"${byte & 0xff}%02x")
      .mkString
  }
}
```

`api/src/main/scala/com/ruchij/api/services/fallback/FallbackSyncDao.scala` (Task 5 adds the trait and Doobie
implementation to this file):

```scala
package com.ruchij.api.services.fallback

import com.ruchij.core.daos.scheduling.models.ScheduledVideoDownload

final case class SyncedVideo(scheduledVideoDownload: ScheduledVideoDownload, userIds: List[String])
```

`api/src/main/scala/com/ruchij/api/services/fallback/ScheduledVideoUpserts.scala`:

```scala
package com.ruchij.api.services.fallback

import com.ruchij.api.services.fallback.models.ScheduledVideoUpsert

import java.time.Instant

object ScheduledVideoUpserts {
  def from(syncedVideo: SyncedVideo, capturedAt: Instant): ScheduledVideoUpsert = {
    val video = syncedVideo.scheduledVideoDownload
    val metadata = video.videoMetadata

    val upsert =
      ScheduledVideoUpsert(
        videoId = metadata.id,
        capturedAt = capturedAt,
        hash = "",
        userIds = syncedVideo.userIds.distinct.sorted,
        url = metadata.url.renderString,
        videoSite = metadata.videoSite.name,
        title = metadata.title,
        durationMs = metadata.duration.toMillis,
        sizeBytes = metadata.size,
        status = video.status.entryName,
        scheduledAt = video.scheduledAt,
        completedAt = video.completedAt
      )

    upsert.copy(hash = SyncHash.of(upsert))
  }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `sbt "api/testOnly com.ruchij.api.services.fallback.*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add api
git commit -m "Add fallback sync message encoding, the sync hash and contract fixture tests.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: `fallback-sync-requests` topic and the new publishes

**Files:**
- Create: `api/src/main/scala/com/ruchij/api/services/fallback/models/FallbackSyncRequest.scala`
- Create: `api/src/main/scala/com/ruchij/api/services/fallback/NoOpPublisher.scala`
- Modify: `api/src/main/scala/com/ruchij/api/services/scheduling/ApiSchedulingServiceImpl.scala`
- Modify: `api/src/main/scala/com/ruchij/api/ApiApp.scala` (constructor call only; full wiring in Task 8)
- Modify: `api/src/test/scala/com/ruchij/api/services/scheduling/ApiSchedulingServiceImplSpec.scala`

**Interfaces:**
- Produces:
  - `FallbackSyncRequest(videoId: String)` with `implicit case object FallbackSyncRequestTopic extends
    MessagingTopic[FallbackSyncRequest]` (name `"fallback-sync-requests"`)
  - `NoOpPublisher[F[_]: Applicative, A]()` extends `Publisher[F, A]`
  - `ApiSchedulingServiceImpl` gains constructor parameter `fallbackSyncRequestPublisher:
    Publisher[F, FallbackSyncRequest]` directly after `workerStatusPublisher`

- [ ] **Step 1: Write the failing tests**

In `ApiSchedulingServiceImplSpec`:

1. Add a `fallbackSyncRequestPublisher: StubPublisher[FallbackSyncRequest] = new StubPublisher[FallbackSyncRequest]()`
   parameter to `createService` (after `workerStatusPublisher`) and pass it to the constructor in the same position.
   Keep the returned tuple unchanged; tests that need the stub create it themselves and pass it in.
2. Add these tests (they reuse the spec's existing stubs and `sampleScheduledVideoDownload`):

```scala
  "schedule" should "request a fallback sync when an existing video gains a user" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    val fallbackSyncRequests = new StubPublisher[FallbackSyncRequest]()

    val (service, _, _) = createService(
      fallbackSyncRequestPublisher = fallbackSyncRequests,
      schedulingDao = new StubSchedulingDao(searchResult = Seq(sampleScheduledVideoDownload)),
      videoTitleDao = new StubVideoTitleDao(insertResult = 1),
      videoPermissionDao = new StubVideoPermissionDao(insertResult = 1)
    )

    service.schedule(uri"https://youtube.com/watch?v=abc123", "user-2").map { _ =>
      fallbackSyncRequests.publishedMessages mustBe List(
        FallbackSyncRequest(sampleScheduledVideoDownload.videoMetadata.id)
      )
    }
  }

  it should "not request a fallback sync when the user already had the video" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    val fallbackSyncRequests = new StubPublisher[FallbackSyncRequest]()

    val (service, _, _) = createService(
      fallbackSyncRequestPublisher = fallbackSyncRequests,
      schedulingDao = new StubSchedulingDao(searchResult = Seq(sampleScheduledVideoDownload)),
      videoTitleDao = new StubVideoTitleDao(insertResult = 0),
      videoPermissionDao = new StubVideoPermissionDao(insertResult = 0)
    )

    service.schedule(uri"https://youtube.com/watch?v=abc123", "user-1").map { _ =>
      fallbackSyncRequests.publishedMessages mustBe empty
    }
  }

  "deleteById" should "request a fallback sync when a user removes their copy" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    val fallbackSyncRequests = new StubPublisher[FallbackSyncRequest]()

    val (service, _, _) = createService(
      fallbackSyncRequestPublisher = fallbackSyncRequests,
      schedulingDao = new StubSchedulingDao(getByIdResult = Some(sampleScheduledVideoDownload))
    )

    service.deleteById(sampleScheduledVideoDownload.videoMetadata.id, Some("user-1")).map { _ =>
      fallbackSyncRequests.publishedMessages mustBe List(
        FallbackSyncRequest(sampleScheduledVideoDownload.videoMetadata.id)
      )
    }
  }
```

(Match the stubs' real constructor parameter names — e.g. if `StubSchedulingDao` names its `getById` result
differently, use that name; `StubPublisher.publishedMessages` may be a `ListBuffer`, in which case compare with
`.toList`.)

- [ ] **Step 2: Run the tests to verify they fail**

Run: `sbt "api/testOnly com.ruchij.api.services.scheduling.ApiSchedulingServiceImplSpec"`
Expected: compilation FAIL — `not found: type FallbackSyncRequest`.

- [ ] **Step 3: Implement**

`api/src/main/scala/com/ruchij/api/services/fallback/models/FallbackSyncRequest.scala`:

```scala
package com.ruchij.api.services.fallback.models

import com.ruchij.core.messaging.MessagingTopic
import io.circe.generic.semiauto.deriveCodec
import vulcan.Codec
import vulcan.generic._

final case class FallbackSyncRequest(videoId: String)

object FallbackSyncRequest {
  implicit case object FallbackSyncRequestTopic extends MessagingTopic[FallbackSyncRequest] {
    override val name: String = "fallback-sync-requests"

    override val avroCodec: Codec[FallbackSyncRequest] = Codec.derive[FallbackSyncRequest]

    override val jsonCodec: io.circe.Codec[FallbackSyncRequest] = deriveCodec[FallbackSyncRequest]
  }
}
```

(Mirror `HealthCheckMessage.scala`'s imports exactly if the compiler asks for them — e.g. whether `vulcan.generic._`
is needed for `Codec.derive`.)

`api/src/main/scala/com/ruchij/api/services/fallback/NoOpPublisher.scala`:

```scala
package com.ruchij.api.services.fallback

import cats.Applicative
import com.ruchij.core.messaging.Publisher
import fs2.Pipe

final class NoOpPublisher[F[_]: Applicative, A] extends Publisher[F, A] {
  override val publish: Pipe[F, A, Unit] = _.as(())

  override def publishOne(input: A): F[Unit] = Applicative[F].unit
}
```

In `ApiSchedulingServiceImpl`:

1. Add the constructor parameter `fallbackSyncRequestPublisher: Publisher[F, FallbackSyncRequest]` after
   `workerStatusPublisher`.
2. In `schedule`, where the existing-video branch maps `created`, publish when a permission was added:

```scala
            case scheduledVideoDownload :: _ =>
              existingScheduledVideoDownload(scheduledVideoDownload.videoMetadata, userId)
                .flatTap { created =>
                  fallbackSyncRequestPublisher
                    .publishOne(FallbackSyncRequest(scheduledVideoDownload.videoMetadata.id))
                    .whenA(created)
                }
                .map { created =>
                  if (created) ScheduledVideoResult.NewlyScheduled(scheduledVideoDownload)
                  else ScheduledVideoResult.AlreadyScheduled(scheduledVideoDownload)
                }
```

3. Apply the same `flatTap` to the race branch (lines ~135-144) where `existingScheduledVideoDownload` is called again
   after an insert returned 0.
4. In `deleteById`, replace the non-admin `else Applicative[F].pure(scheduledVideoDownload)` with:

```scala
      } else
        fallbackSyncRequestPublisher
          .publishOne(FallbackSyncRequest(scheduledVideoDownload.videoMetadata.id))
          .as(scheduledVideoDownload)
```

In `ApiApp.program`, pass `new NoOpPublisher[F, FallbackSyncRequest]` as the new constructor argument for now
(Task 8 replaces it with the real publisher when sync is enabled).

- [ ] **Step 4: Run the tests to verify they pass**

Run: `sbt "api/testOnly com.ruchij.api.services.scheduling.*"` and `sbt compile`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add api
git commit -m "Publish fallback sync requests when a user joins or leaves an existing scheduled video.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: AWS edges (SQS transport, request queue, DynamoDB manifest)

**Files:**
- Create: `api/src/main/scala/com/ruchij/api/services/fallback/aws/FallbackSyncAwsClients.scala`
- Create: `api/src/main/scala/com/ruchij/api/services/fallback/aws/FallbackSyncTransport.scala`
- Create: `api/src/main/scala/com/ruchij/api/services/fallback/aws/FallbackRequestQueue.scala`
- Create: `api/src/main/scala/com/ruchij/api/services/fallback/aws/FallbackManifestReader.scala`
- Create: `api/src/test/scala/com/ruchij/api/external/containers/ElasticMqContainer.scala`
- Create: `api/src/test/scala/com/ruchij/api/external/containers/DynamoDbLocalContainer.scala`
- Create: `api/src/test/scala/com/ruchij/api/services/fallback/aws/FallbackAwsSpec.scala`

**Interfaces:**
- Consumes: `FallbackSyncSettings` (Task 1), `SyncJson`, messages (Task 2).
- Produces:
  - `FallbackSyncAwsClients(sqs: SqsAsyncClient, dynamoDb: DynamoDbAsyncClient)` and
    `FallbackSyncAwsClients.create[F[_]: Sync](settings: FallbackSyncSettings, credentialsProvider:
    AwsCredentialsProvider = DefaultCredentialsProvider.builder().build()): Resource[F, FallbackSyncAwsClients]`
  - `trait FallbackSyncTransport[F[_]] { def send(messages: List[MainToFallbackMessage]): F[Unit] }` and
    `SqsFallbackSyncTransport[F[_]: Async](sqsClient: SqsAsyncClient, queueUrl: String)`
  - `ReceivedMessage(body: String, receiptHandle: String, receiveCount: Int)`;
    `trait FallbackRequestQueue[F[_]]` with `receive: F[List[ReceivedMessage]]` and
    `delete(receiptHandle: String): F[Unit]`;
    `SqsFallbackRequestQueue[F[_]: Async](sqsClient: SqsAsyncClient, queueUrl: String, waitTimeSeconds: Int = 20)`
  - `ManifestEntry(hash: String, capturedAt: Instant)`;
    `trait FallbackManifestReader[F[_]] { def manifest: F[Map[String, ManifestEntry]] }`;
    `DynamoDbFallbackManifestReader[F[_]: Async](dynamoDbClient: DynamoDbAsyncClient, tableName: String)`

- [ ] **Step 1: Write the containers and the failing integration test**

`api/src/test/scala/com/ruchij/api/external/containers/ElasticMqContainer.scala`:

```scala
package com.ruchij.api.external.containers

import cats.effect.{Resource, Sync}
import com.ruchij.core.external.containers.ContainerCoreResourcesProvider
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

class ElasticMqContainer
    extends GenericContainer[ElasticMqContainer](DockerImageName.parse("softwaremill/elasticmq-native:latest")) {
  withExposedPorts(9324)
}

object ElasticMqContainer {
  /** Resolves to the SQS-compatible endpoint URL. */
  def create[F[_]: Sync]: Resource[F, String] =
    ContainerCoreResourcesProvider
      .start(new ElasticMqContainer)
      .evalMap(container => Sync[F].blocking(s"http://${container.getHost}:${container.getMappedPort(9324)}"))
}
```

`api/src/test/scala/com/ruchij/api/external/containers/DynamoDbLocalContainer.scala`:

```scala
package com.ruchij.api.external.containers

import cats.effect.{Resource, Sync}
import com.ruchij.core.external.containers.ContainerCoreResourcesProvider
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

class DynamoDbLocalContainer
    extends GenericContainer[DynamoDbLocalContainer](DockerImageName.parse("amazon/dynamodb-local:latest")) {
  withExposedPorts(8000)
}

object DynamoDbLocalContainer {
  def create[F[_]: Sync]: Resource[F, String] =
    ContainerCoreResourcesProvider
      .start(new DynamoDbLocalContainer)
      .evalMap(container => Sync[F].blocking(s"http://${container.getHost}:${container.getMappedPort(8000)}"))
}
```

`api/src/test/scala/com/ruchij/api/services/fallback/aws/FallbackAwsSpec.scala`:

```scala
package com.ruchij.api.services.fallback.aws

import cats.effect.{IO, Resource}
import com.ruchij.api.config.FallbackSyncSettings
import com.ruchij.api.external.containers.{DynamoDbLocalContainer, ElasticMqContainer}
import com.ruchij.api.services.fallback.FallbackSyncTestData.fixtureUpsert
import com.ruchij.api.services.fallback.models.{ScheduledVideoRemoval, SyncJson}
import com.ruchij.core.test.IOSupport.runIO
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.services.dynamodb.model._
import software.amazon.awssdk.services.sqs.model.{CreateQueueRequest, SendMessageRequest}

import java.time.Instant
import scala.jdk.CollectionConverters._

class FallbackAwsSpec extends AnyFlatSpec with Matchers {

  private val credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))

  private def clients(endpoint: String): Resource[IO, FallbackSyncAwsClients] =
    FallbackSyncAwsClients.create[IO](
      FallbackSyncSettings("unused", "unused", "unused", "ap-southeast-2", Some(endpoint)),
      credentials
    )

  private def queueUrl(aws: FallbackSyncAwsClients, name: String): IO[String] =
    IO.fromCompletableFuture(IO(aws.sqs.createQueue(CreateQueueRequest.builder().queueName(name).build())))
      .map(_.queueUrl())

  "SqsFallbackSyncTransport" should "send every message, batching more than ten" in runIO {
    (ElasticMqContainer.create[IO].flatMap(clients)).use { aws =>
      for {
        url <- queueUrl(aws, "main-to-fallback")
        messages = (1 to 12).toList.map(index => ScheduledVideoRemoval(s"video-$index", Instant.EPOCH))
        _ <- new SqsFallbackSyncTransport[IO](aws.sqs, url).send(messages)
        queue = new SqsFallbackRequestQueue[IO](aws.sqs, url, waitTimeSeconds = 1)
        received <- queue.receive.flatMap(first => queue.receive.map(first ++ _))
      } yield received.map(_.body).toSet mustBe messages.map(SyncJson.encode).toSet
    }
  }

  it should "fail when SQS rejects any entry of a batch" in runIO {
    (ElasticMqContainer.create[IO].flatMap(clients)).use { aws =>
      for {
        url <- queueUrl(aws, "main-to-fallback")
        // A body over the SQS limit is rejected per entry while the batch call itself succeeds.
        oversized = ScheduledVideoRemoval("x" * (1024 * 1024 + 1), Instant.EPOCH)
        result <- new SqsFallbackSyncTransport[IO](aws.sqs, url).send(List(oversized)).attempt
      } yield result.isLeft mustBe true
    }
  }

  "SqsFallbackRequestQueue" should "report the receive count and delete handled messages" in runIO {
    (ElasticMqContainer.create[IO].flatMap(clients)).use { aws =>
      for {
        url <- queueUrl(aws, "fallback-to-main")
        _ <- IO.fromCompletableFuture(
          IO(aws.sqs.sendMessage(SendMessageRequest.builder().queueUrl(url).messageBody("hello").build()))
        )
        queue = new SqsFallbackRequestQueue[IO](aws.sqs, url, waitTimeSeconds = 1)
        received <- queue.receive
        _ <- received.traverse_(message => queue.delete(message.receiptHandle))
        afterDelete <- queue.receive
      } yield {
        received.map(message => (message.body, message.receiveCount)) mustBe List(("hello", 1))
        afterDelete mustBe empty
      }
    }
  }

  "DynamoDbFallbackManifestReader" should "return live video items only, across scan pages" in runIO {
    (DynamoDbLocalContainer.create[IO].flatMap(clients)).use { aws =>
      def put(item: Map[String, AttributeValue]): IO[Unit] =
        IO.fromCompletableFuture(
          IO(aws.dynamoDb.putItem(PutItemRequest.builder().tableName("videos").item(item.asJava).build()))
        ).void

      def s(value: String): AttributeValue = AttributeValue.builder().s(value).build()
      def bool(value: Boolean): AttributeValue = AttributeValue.builder().bool(value).build()

      val createTable =
        CreateTableRequest
          .builder()
          .tableName("videos")
          .billingMode(BillingMode.PAY_PER_REQUEST)
          .keySchema(
            KeySchemaElement.builder().attributeName("PK").keyType(KeyType.HASH).build(),
            KeySchemaElement.builder().attributeName("SK").keyType(KeyType.RANGE).build()
          )
          .attributeDefinitions(
            AttributeDefinition.builder().attributeName("PK").attributeType(ScalarAttributeType.S).build(),
            AttributeDefinition.builder().attributeName("SK").attributeType(ScalarAttributeType.S).build()
          )
          .build()

      for {
        _ <- IO.fromCompletableFuture(IO(aws.dynamoDb.createTable(createTable)))
        _ <- (1 to 30).toList.traverse_ { index =>
          put(
            Map(
              "PK" -> s(s"VIDEO#video-$index"),
              "SK" -> s("VIDEO"),
              "hash" -> s(s"hash-$index"),
              "capturedAt" -> s("2026-09-26T08:15:30.123Z"),
              "deleted" -> bool(false),
              "padding" -> s("p" * 40000)
            )
          )
        }
        _ <- put(
          Map(
            "PK" -> s("VIDEO#gone"),
            "SK" -> s("VIDEO"),
            "capturedAt" -> s("2026-09-26T08:15:30.123Z"),
            "deleted" -> bool(true)
          )
        )
        _ <- put(Map("PK" -> s("USER#user-1"), "SK" -> s("VIDEO#2026#video-1")))
        manifest <- new DynamoDbFallbackManifestReader[IO](aws.dynamoDb, "videos").manifest
      } yield {
        manifest.keySet mustBe (1 to 30).map(index => s"video-$index").toSet
        manifest("video-7") mustBe ManifestEntry("hash-7", Instant.parse("2026-09-26T08:15:30.123Z"))
      }
    }
  }

  "The contract fixture" should "fit in one SQS message" in {
    SyncJson.encode(fixtureUpsert).length must be < 1024 * 1024
  }
}
```

The 30 × 40 KB items exceed DynamoDB's 1 MB scan page, so the test exercises pagination. Add
`import cats.implicits._` for `traverse_`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `sbt "api/testOnly com.ruchij.api.services.fallback.aws.FallbackAwsSpec"`
Expected: compilation FAIL — `not found: type FallbackSyncAwsClients`.

- [ ] **Step 3: Implement**

`api/src/main/scala/com/ruchij/api/services/fallback/aws/FallbackSyncAwsClients.scala`:

```scala
package com.ruchij.api.services.fallback.aws

import cats.effect.{Resource, Sync}
import com.ruchij.api.config.FallbackSyncSettings
import software.amazon.awssdk.auth.credentials.{AwsCredentialsProvider, DefaultCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.sqs.SqsAsyncClient

import java.net.URI

final case class FallbackSyncAwsClients(sqs: SqsAsyncClient, dynamoDb: DynamoDbAsyncClient)

object FallbackSyncAwsClients {
  def create[F[_]: Sync](
    settings: FallbackSyncSettings,
    credentialsProvider: AwsCredentialsProvider = DefaultCredentialsProvider.builder().build()
  ): Resource[F, FallbackSyncAwsClients] = {
    val region = Region.of(settings.awsRegion)
    val endpoint = settings.awsEndpointUrl.map(URI.create)

    for {
      sqs <- Resource.fromAutoCloseable {
        Sync[F].delay {
          val builder = SqsAsyncClient.builder().region(region).credentialsProvider(credentialsProvider)
          endpoint.fold(builder)(builder.endpointOverride).build()
        }
      }
      dynamoDb <- Resource.fromAutoCloseable {
        Sync[F].delay {
          val builder = DynamoDbAsyncClient.builder().region(region).credentialsProvider(credentialsProvider)
          endpoint.fold(builder)(builder.endpointOverride).build()
        }
      }
    } yield FallbackSyncAwsClients(sqs, dynamoDb)
  }
}
```

`api/src/main/scala/com/ruchij/api/services/fallback/aws/FallbackSyncTransport.scala`:

```scala
package com.ruchij.api.services.fallback.aws

import cats.effect.{Async, Sync}
import cats.implicits._
import com.ruchij.api.services.fallback.models.{MainToFallbackMessage, SyncJson}
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.{SendMessageBatchRequest, SendMessageBatchRequestEntry}

import scala.jdk.CollectionConverters._

trait FallbackSyncTransport[F[_]] {
  def send(messages: List[MainToFallbackMessage]): F[Unit]
}

class SqsFallbackSyncTransport[F[_]: Async](sqsClient: SqsAsyncClient, queueUrl: String)
    extends FallbackSyncTransport[F] {

  override def send(messages: List[MainToFallbackMessage]): F[Unit] =
    messages.grouped(10).toList.traverse_ { batch =>
      val entries =
        batch.zipWithIndex.map {
          case (message, index) =>
            SendMessageBatchRequestEntry.builder().id(index.toString).messageBody(SyncJson.encode(message)).build()
        }

      val request = SendMessageBatchRequest.builder().queueUrl(queueUrl).entries(entries.asJava).build()

      Async[F].fromCompletableFuture(Sync[F].delay(sqsClient.sendMessageBatch(request))).flatMap { response =>
        // SendMessageBatch reports per-entry failures inside a successful response.
        if (response.failed().isEmpty) Async[F].unit
        else
          Async[F].raiseError(
            new IllegalStateException(
              s"SQS rejected ${response.failed().size()} of ${entries.size} messages: " +
                response.failed().asScala.map(_.message()).mkString("; ")
            )
          )
      }
    }
}
```

`api/src/main/scala/com/ruchij/api/services/fallback/aws/FallbackRequestQueue.scala`:

```scala
package com.ruchij.api.services.fallback.aws

import cats.effect.{Async, Sync}
import cats.implicits._
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.{
  DeleteMessageRequest,
  MessageSystemAttributeName,
  ReceiveMessageRequest
}

import scala.jdk.CollectionConverters._

final case class ReceivedMessage(body: String, receiptHandle: String, receiveCount: Int)

trait FallbackRequestQueue[F[_]] {
  def receive: F[List[ReceivedMessage]]

  def delete(receiptHandle: String): F[Unit]
}

class SqsFallbackRequestQueue[F[_]: Async](sqsClient: SqsAsyncClient, queueUrl: String, waitTimeSeconds: Int = 20)
    extends FallbackRequestQueue[F] {

  override def receive: F[List[ReceivedMessage]] = {
    val request =
      ReceiveMessageRequest
        .builder()
        .queueUrl(queueUrl)
        .maxNumberOfMessages(10)
        .waitTimeSeconds(waitTimeSeconds)
        .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)
        .build()

    Async[F].fromCompletableFuture(Sync[F].delay(sqsClient.receiveMessage(request))).map { response =>
      response.messages().asScala.toList.map { message =>
        val receiveCount =
          message
            .attributes()
            .asScala
            .get(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)
            .flatMap(_.toIntOption)
            .getOrElse(1)

        ReceivedMessage(message.body(), message.receiptHandle(), receiveCount)
      }
    }
  }

  override def delete(receiptHandle: String): F[Unit] =
    Async[F]
      .fromCompletableFuture(
        Sync[F].delay(
          sqsClient.deleteMessage(
            DeleteMessageRequest.builder().queueUrl(queueUrl).receiptHandle(receiptHandle).build()
          )
        )
      )
      .void
}
```

`api/src/main/scala/com/ruchij/api/services/fallback/aws/FallbackManifestReader.scala`:

```scala
package com.ruchij.api.services.fallback.aws

import cats.effect.{Async, Sync}
import cats.implicits._
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.{AttributeValue, ScanRequest}

import java.time.Instant
import java.util.{Map => JMap}
import scala.jdk.CollectionConverters._

final case class ManifestEntry(hash: String, capturedAt: Instant)

trait FallbackManifestReader[F[_]] {
  def manifest: F[Map[String, ManifestEntry]]
}

class DynamoDbFallbackManifestReader[F[_]: Async](dynamoDbClient: DynamoDbAsyncClient, tableName: String)
    extends FallbackManifestReader[F] {

  override def manifest: F[Map[String, ManifestEntry]] = page(None, Map.empty)

  private def page(
    exclusiveStartKey: Option[JMap[String, AttributeValue]],
    accumulated: Map[String, ManifestEntry]
  ): F[Map[String, ManifestEntry]] = {
    val builder =
      ScanRequest
        .builder()
        .tableName(tableName)
        .projectionExpression("PK, #hash, capturedAt")
        .filterExpression("SK = :video AND (attribute_not_exists(deleted) OR deleted = :false)")
        .expressionAttributeNames(Map("#hash" -> "hash").asJava)
        .expressionAttributeValues(
          Map(
            ":video" -> AttributeValue.builder().s("VIDEO").build(),
            ":false" -> AttributeValue.builder().bool(false).build()
          ).asJava
        )

    val request = exclusiveStartKey.fold(builder)(builder.exclusiveStartKey).build()

    Async[F].fromCompletableFuture(Sync[F].delay(dynamoDbClient.scan(request))).flatMap { response =>
      val entries =
        response.items().asScala.toList.flatMap { item =>
          for {
            partitionKey <- Option(item.get("PK")).map(_.s())
            hash <- Option(item.get("hash")).map(_.s())
            capturedAt <- Option(item.get("capturedAt")).map(_.s())
          } yield partitionKey.stripPrefix("VIDEO#") -> ManifestEntry(hash, Instant.parse(capturedAt))
        }

      val next = accumulated ++ entries

      if (response.hasLastEvaluatedKey && !response.lastEvaluatedKey().isEmpty)
        page(Some(response.lastEvaluatedKey()), next)
      else Async[F].pure(next)
    }
  }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `sbt "api/testOnly com.ruchij.api.services.fallback.aws.FallbackAwsSpec"` (Docker must be running)
Expected: PASS. If ElasticMQ accepts the oversized body, lower the test's body to exceed its configured limit or
replace the case with a batch containing two entries with the same `id`, which SQS rejects with
`BatchEntryIdsNotDistinct` — the point is a non-empty `failed()` list.

- [ ] **Step 5: Commit**

```bash
git add api
git commit -m "Add the SQS and DynamoDB edges for fallback sync, tested against ElasticMQ and DynamoDB Local.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: DB reads, coordination and the publisher

**Files:**
- Modify: `api/src/main/scala/com/ruchij/api/services/fallback/FallbackSyncDao.scala`
- Create: `api/src/main/scala/com/ruchij/api/services/fallback/FallbackSyncCoordination.scala`
- Create: `api/src/main/scala/com/ruchij/api/services/fallback/FallbackSyncPublisher.scala`
- Create: `api/src/test/scala/com/ruchij/api/services/fallback/FallbackSyncStubs.scala`
- Create: `api/src/test/scala/com/ruchij/api/services/fallback/DoobieFallbackSyncDaoSpec.scala`
- Create: `api/src/test/scala/com/ruchij/api/services/fallback/FallbackSyncCoordinationSpec.scala`
- Create: `api/src/test/scala/com/ruchij/api/services/fallback/FallbackSyncPublisherSpec.scala`

**Interfaces:**
- Consumes: `SyncedVideo`, `ScheduledVideoUpserts` (Task 2); `FallbackSyncTransport` (Task 4).
- Produces:
  - `trait FallbackSyncDao[F[_]]` with `findById(videoId: String): F[Option[SyncedVideo]]` and
    `findAll: F[List[SyncedVideo]]`
  - `class DoobieFallbackSyncDao(schedulingDao: SchedulingDao[ConnectionIO], videoPermissionDao:
    VideoPermissionDao[ConnectionIO], pageSize: Int = 500) extends FallbackSyncDao[ConnectionIO]`
  - `class FallbackSyncCoordination[F[_]: MonadCancelThrow](keyValueStore: KeyValueStore[F], lockTtl:
    FiniteDuration = 30.minutes)` with `markReconcileNeeded`, `isReconcileNeeded: F[Boolean]`,
    `clearReconcileNeeded`, `withReconcileLock[A](owner: String)(fa: F[A]): F[Option[A]]`
  - `class FallbackSyncPublisher[F[_]: Temporal: Clock, T[_]](fallbackSyncDao: FallbackSyncDao[T], transport:
    FallbackSyncTransport[F], coordination: FallbackSyncCoordination[F], window: FiniteDuration = 30.seconds,
    maxBatchSize: Int = 500, retryDelays: List[FiniteDuration] = List(1.second, 5.seconds, 25.seconds))(implicit
    transaction: T ~> F)` with `messagesFor(videoIds: List[String]): F[List[MainToFallbackMessage]]`,
    `publish(videoIds: List[String]): F[Unit]`,
    `pipeline[A](subscriber: Subscriber[F, A], groupId: String)(videoId: A => String): Stream[F, Unit]`
  - Test stubs (Task 6 and 7 reuse them): `StubFallbackSyncDao`, `RecordingTransport`, `FlakyTransport`

- [ ] **Step 1: Write the stubs and failing tests**

`api/src/test/scala/com/ruchij/api/services/fallback/FallbackSyncStubs.scala`:

```scala
package com.ruchij.api.services.fallback

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.~>
import com.ruchij.api.services.fallback.aws.FallbackSyncTransport
import com.ruchij.api.services.fallback.models.MainToFallbackMessage

object FallbackSyncStubs {
  implicit val identityTransaction: IO ~> IO = new (IO ~> IO) {
    override def apply[A](fa: IO[A]): IO[A] = fa
  }

  final class StubFallbackSyncDao(videos: Ref[IO, Map[String, SyncedVideo]]) extends FallbackSyncDao[IO] {
    override def findById(videoId: String): IO[Option[SyncedVideo]] = videos.get.map(_.get(videoId))

    override def findAll: IO[List[SyncedVideo]] = videos.get.map(_.values.toList)

    def remove(videoId: String): IO[Unit] = videos.update(_ - videoId)
  }

  object StubFallbackSyncDao {
    def apply(videos: SyncedVideo*): IO[StubFallbackSyncDao] =
      Ref
        .of[IO, Map[String, SyncedVideo]] {
          videos.map(video => video.scheduledVideoDownload.videoMetadata.id -> video).toMap
        }
        .map(new StubFallbackSyncDao(_))
  }

  final class RecordingTransport(sent: Ref[IO, List[MainToFallbackMessage]]) extends FallbackSyncTransport[IO] {
    override def send(messages: List[MainToFallbackMessage]): IO[Unit] = sent.update(_ ++ messages)

    val messages: IO[List[MainToFallbackMessage]] = sent.get
  }

  object RecordingTransport {
    def apply(): IO[RecordingTransport] = Ref.of[IO, List[MainToFallbackMessage]](Nil).map(new RecordingTransport(_))
  }

  /** Fails the first `failures` sends, then records. */
  final class FlakyTransport(remainingFailures: Ref[IO, Int], val recording: RecordingTransport)
      extends FallbackSyncTransport[IO] {
    override def send(messages: List[MainToFallbackMessage]): IO[Unit] =
      remainingFailures.modify(n => (n - 1, n)).flatMap { n =>
        if (n > 0) IO.raiseError(new RuntimeException("SQS unavailable")) else recording.send(messages)
      }
  }

  object FlakyTransport {
    def apply(failures: Int): IO[FlakyTransport] =
      (Ref.of[IO, Int](failures), RecordingTransport()).mapN(new FlakyTransport(_, _))
  }
}
```

(Add `import cats.implicits._` for `mapN`.)

`api/src/test/scala/com/ruchij/api/services/fallback/FallbackSyncCoordinationSpec.scala`:

```scala
package com.ruchij.api.services.fallback

import cats.effect.IO
import com.ruchij.core.kv.InMemoryKeyValueStore
import com.ruchij.core.test.IOSupport.runIO
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class FallbackSyncCoordinationSpec extends AnyFlatSpec with Matchers {

  "FallbackSyncCoordination" should "set, read and clear the reconcile flag" in runIO {
    val coordination = new FallbackSyncCoordination[IO](new InMemoryKeyValueStore[IO])

    for {
      before <- coordination.isReconcileNeeded
      _ <- coordination.markReconcileNeeded
      marked <- coordination.isReconcileNeeded
      _ <- coordination.clearReconcileNeeded
      cleared <- coordination.isReconcileNeeded
    } yield (before, marked, cleared) mustBe ((false, true, false))
  }

  it should "let only one owner hold the reconcile lock and release it afterwards" in runIO {
    val coordination = new FallbackSyncCoordination[IO](new InMemoryKeyValueStore[IO])

    for {
      nested <- coordination.withReconcileLock("instance-a") {
        coordination.withReconcileLock("instance-b")(IO.pure("b ran"))
      }
      afterRelease <- coordination.withReconcileLock("instance-b")(IO.pure("b ran"))
    } yield {
      nested mustBe Some(None)
      afterRelease mustBe Some("b ran")
    }
  }

  it should "release the lock when the work fails" in runIO {
    val coordination = new FallbackSyncCoordination[IO](new InMemoryKeyValueStore[IO])

    for {
      failed <- coordination.withReconcileLock("instance-a")(IO.raiseError[Unit](new RuntimeException)).attempt
      next <- coordination.withReconcileLock("instance-b")(IO.pure(1))
    } yield {
      failed.isLeft mustBe true
      next mustBe Some(1)
    }
  }
}
```

`api/src/test/scala/com/ruchij/api/services/fallback/FallbackSyncPublisherSpec.scala`:

```scala
package com.ruchij.api.services.fallback

import cats.effect.IO
import com.ruchij.api.services.fallback.FallbackSyncStubs._
import com.ruchij.api.services.fallback.FallbackSyncTestData.{capturedAt, scheduledVideoDownload}
import com.ruchij.api.services.fallback.models.{ScheduledVideoRemoval, ScheduledVideoUpsert}
import com.ruchij.core.kv.InMemoryKeyValueStore
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.test.Providers
import com.ruchij.core.types.Clock
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.duration._

class FallbackSyncPublisherSpec extends AnyFlatSpec with Matchers {
  implicit val clock: Clock[IO] = Providers.stubClock[IO](capturedAt)

  private val noDelays = List(Duration.Zero, Duration.Zero, Duration.Zero)

  "FallbackSyncPublisher.messagesFor" should "turn found rows into upserts and missing rows into removals" in runIO {
    for {
      dao <- StubFallbackSyncDao(SyncedVideo(scheduledVideoDownload("video-1"), List("user-1")))
      transport <- RecordingTransport()
      coordination = new FallbackSyncCoordination[IO](new InMemoryKeyValueStore[IO])
      publisher = new FallbackSyncPublisher[IO, IO](dao, transport, coordination, retryDelays = noDelays)
      messages <- publisher.messagesFor(List("video-1", "missing", "video-1"))
    } yield {
      messages.size mustBe 2
      messages.head mustBe a[ScheduledVideoUpsert]
      messages(1) mustBe ScheduledVideoRemoval("missing", capturedAt)
    }
  }

  "FallbackSyncPublisher.publish" should "retry a failing send" in runIO {
    for {
      dao <- StubFallbackSyncDao(SyncedVideo(scheduledVideoDownload("video-1"), List("user-1")))
      transport <- FlakyTransport(failures = 2)
      coordination = new FallbackSyncCoordination[IO](new InMemoryKeyValueStore[IO])
      publisher = new FallbackSyncPublisher[IO, IO](dao, transport, coordination, retryDelays = noDelays)
      _ <- publisher.publish(List("video-1"))
      sent <- transport.recording.messages
      flagged <- coordination.isReconcileNeeded
    } yield {
      sent.size mustBe 1
      flagged mustBe false
    }
  }

  it should "flag a reconcile instead of failing when every retry fails" in runIO {
    for {
      dao <- StubFallbackSyncDao(SyncedVideo(scheduledVideoDownload("video-1"), List("user-1")))
      transport <- FlakyTransport(failures = 10)
      coordination = new FallbackSyncCoordination[IO](new InMemoryKeyValueStore[IO])
      publisher = new FallbackSyncPublisher[IO, IO](dao, transport, coordination, retryDelays = noDelays)
      result <- publisher.publish(List("video-1")).attempt
      flagged <- coordination.isReconcileNeeded
    } yield {
      result mustBe Right(())
      flagged mustBe true
    }
  }
}
```

`api/src/test/scala/com/ruchij/api/services/fallback/DoobieFallbackSyncDaoSpec.scala` (modelled on
`core/src/test/scala/com/ruchij/core/daos/permission/DoobieVideoPermissionDaoSpec.scala`):

```scala
package com.ruchij.api.services.fallback

import cats.effect.IO
import cats.implicits._
import com.ruchij.api.services.fallback.FallbackSyncTestData.scheduledVideoDownload
import com.ruchij.core.daos.doobie.DoobieCustomMappings._
import com.ruchij.core.daos.permission.DoobieVideoPermissionDao
import com.ruchij.core.daos.permission.models.VideoPermission
import com.ruchij.core.daos.resource.DoobieFileResourceDao
import com.ruchij.core.daos.scheduling.DoobieSchedulingDao
import com.ruchij.core.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.core.daos.videometadata.DoobieVideoMetadataDao
import com.ruchij.core.external.embedded.EmbeddedCoreResourcesProvider
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.types.Clock
import doobie.ConnectionIO
import doobie.implicits._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.time.Instant

class DoobieFallbackSyncDaoSpec extends AnyFlatSpec with Matchers {

  private def insertTestUser(userId: String, timestamp: Instant): ConnectionIO[Int] =
    sql"""
      INSERT INTO api_user (id, created_at, first_name, last_name, email, role)
        VALUES ($userId, $timestamp, 'Test', 'User', ${s"$userId@test.com"}, 'User')
    """.update.run

  private def insertVideo(video: ScheduledVideoDownload): ConnectionIO[Unit] =
    DoobieFileResourceDao.insert(video.videoMetadata.thumbnail) *>
      DoobieVideoMetadataDao.insert(video.videoMetadata) *>
      DoobieSchedulingDao.insert(video).void

  "DoobieFallbackSyncDao" should "read videos with their permission user ids" in runIO {
    new EmbeddedCoreResourcesProvider[IO].transactor.use { transaction =>
      val dao = new DoobieFallbackSyncDao(DoobieSchedulingDao, DoobieVideoPermissionDao, pageSize = 2)
      val videos = (1 to 5).toList.map(index => scheduledVideoDownload(s"video-$index"))

      for {
        timestamp <- Clock[IO].timestamp
        _ <- transaction {
          insertTestUser("user-1", timestamp) *> insertTestUser("user-2", timestamp) *>
            videos.traverse_(insertVideo) *>
            DoobieVideoPermissionDao.insert(VideoPermission(timestamp, "video-1", "user-1")) *>
            DoobieVideoPermissionDao.insert(VideoPermission(timestamp, "video-1", "user-2")).void
        }
        one <- transaction(dao.findById("video-1"))
        missing <- transaction(dao.findById("nope"))
        all <- transaction(dao.findAll)
      } yield {
        one.map(_.userIds.sorted) mustBe Some(List("user-1", "user-2"))
        missing mustBe None
        all.map(_.scheduledVideoDownload.videoMetadata.id).sorted mustBe videos.map(_.videoMetadata.id).sorted
        all.find(_.scheduledVideoDownload.videoMetadata.id == "video-2").map(_.userIds) mustBe Some(Nil)
      }
    }
  }
}
```

`pageSize = 2` with five videos exercises the paging loop.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `sbt "api/testOnly com.ruchij.api.services.fallback.*"`
Expected: compilation FAIL — `not found: type FallbackSyncCoordination` / `FallbackSyncPublisher` /
`DoobieFallbackSyncDao`.

- [ ] **Step 3: Implement**

Replace `api/src/main/scala/com/ruchij/api/services/fallback/FallbackSyncDao.scala`:

```scala
package com.ruchij.api.services.fallback

import cats.implicits._
import com.ruchij.core.daos.permission.VideoPermissionDao
import com.ruchij.core.daos.scheduling.SchedulingDao
import com.ruchij.core.daos.scheduling.models.{RangeValue, ScheduledVideoDownload}
import com.ruchij.core.services.models.{Order, SortBy}
import doobie.free.connection.ConnectionIO

import scala.concurrent.duration.FiniteDuration

final case class SyncedVideo(scheduledVideoDownload: ScheduledVideoDownload, userIds: List[String])

trait FallbackSyncDao[F[_]] {
  def findById(videoId: String): F[Option[SyncedVideo]]

  def findAll: F[List[SyncedVideo]]
}

class DoobieFallbackSyncDao(
  schedulingDao: SchedulingDao[ConnectionIO],
  videoPermissionDao: VideoPermissionDao[ConnectionIO],
  pageSize: Int = 500
) extends FallbackSyncDao[ConnectionIO] {

  override def findById(videoId: String): ConnectionIO[Option[SyncedVideo]] =
    schedulingDao.getById(videoId, None).flatMap {
      _.traverse { video =>
        videoPermissionDao
          .find(None, Some(videoId))
          .map(permissions => SyncedVideo(video, permissions.map(_.userId).toList))
      }
    }

  override def findAll: ConnectionIO[List[SyncedVideo]] =
    for {
      videos <- allVideos(0, Vector.empty)
      permissions <- videoPermissionDao.find(None, None)
      userIdsByVideo = permissions.groupMap(_.scheduledVideoDownloadId)(_.userId)
    } yield
      videos.toList.map { video =>
        SyncedVideo(video, userIdsByVideo.getOrElse(video.videoMetadata.id, Seq.empty).toList)
      }

  private def allVideos(
    pageNumber: Int,
    accumulated: Vector[ScheduledVideoDownload]
  ): ConnectionIO[Vector[ScheduledVideoDownload]] =
    schedulingDao
      .search(
        None,
        None,
        RangeValue.all[FiniteDuration],
        RangeValue.all[Long],
        pageNumber,
        pageSize,
        SortBy.Date,
        Order.Ascending,
        None,
        None,
        None
      )
      .flatMap { page =>
        val next = accumulated ++ page
        if (page.size < pageSize) next.pure[ConnectionIO] else allVideos(pageNumber + 1, next)
      }
}
```

`api/src/main/scala/com/ruchij/api/services/fallback/FallbackSyncCoordination.scala`:

```scala
package com.ruchij.api.services.fallback

import cats.effect.MonadCancelThrow
import cats.implicits._
import com.ruchij.core.kv.KeyValueStore

import scala.concurrent.duration._

class FallbackSyncCoordination[F[_]: MonadCancelThrow](
  keyValueStore: KeyValueStore[F],
  lockTtl: FiniteDuration = 30.minutes
) {
  import FallbackSyncCoordination._

  def markReconcileNeeded: F[Unit] = keyValueStore.put[String, String](ReconcileNeededKey, "true", None).void

  def isReconcileNeeded: F[Boolean] = keyValueStore.get[String, String](ReconcileNeededKey).map(_.nonEmpty)

  def clearReconcileNeeded: F[Unit] = keyValueStore.remove[String](ReconcileNeededKey).void

  /**
    * Best-effort mutual exclusion: two instances can both acquire under a race, which is harmless because every
    * sync message is idempotent. It only avoids routinely doing the same work three times.
    */
  def withReconcileLock[A](owner: String)(fa: F[A]): F[Option[A]] =
    tryAcquire(owner).flatMap { acquired =>
      if (acquired) MonadCancelThrow[F].guarantee(fa, release(owner)).map(Option(_))
      else MonadCancelThrow[F].pure(Option.empty[A])
    }

  private def tryAcquire(owner: String): F[Boolean] =
    keyValueStore.get[String, String](ReconcileLockKey).flatMap {
      case Some(_) => MonadCancelThrow[F].pure(false)
      case None =>
        keyValueStore.put[String, String](ReconcileLockKey, owner, Some(lockTtl)) *>
          keyValueStore.get[String, String](ReconcileLockKey).map(_.contains(owner))
    }

  private def release(owner: String): F[Unit] =
    keyValueStore.get[String, String](ReconcileLockKey).flatMap { current =>
      keyValueStore.remove[String](ReconcileLockKey).void.whenA(current.contains(owner))
    }
}

object FallbackSyncCoordination {
  val ReconcileNeededKey = "fallback-sync::reconcile-needed"
  val ReconcileLockKey = "fallback-sync::reconcile-lock"
}
```

`api/src/main/scala/com/ruchij/api/services/fallback/FallbackSyncPublisher.scala`:

```scala
package com.ruchij.api.services.fallback

import cats.effect.Temporal
import cats.implicits._
import cats.~>
import com.ruchij.api.services.fallback.aws.FallbackSyncTransport
import com.ruchij.api.services.fallback.models.{MainToFallbackMessage, ScheduledVideoRemoval}
import com.ruchij.core.logging.Logger
import com.ruchij.core.messaging.Subscriber
import com.ruchij.core.types.Clock
import fs2.Stream

import scala.concurrent.duration._

class FallbackSyncPublisher[F[_]: Temporal: Clock, T[_]](
  fallbackSyncDao: FallbackSyncDao[T],
  transport: FallbackSyncTransport[F],
  coordination: FallbackSyncCoordination[F],
  window: FiniteDuration = 30.seconds,
  maxBatchSize: Int = 500,
  retryDelays: List[FiniteDuration] = List(1.second, 5.seconds, 25.seconds)
)(implicit transaction: T ~> F) {
  private val logger = Logger[FallbackSyncPublisher[F, T]]

  /** Reads the current state at send time, so a late duplicate still carries fresh data. */
  def messagesFor(videoIds: List[String]): F[List[MainToFallbackMessage]] =
    Clock[F].timestamp.flatMap { capturedAt =>
      videoIds.distinct.traverse { videoId =>
        transaction(fallbackSyncDao.findById(videoId)).map[MainToFallbackMessage] {
          case Some(syncedVideo) => ScheduledVideoUpserts.from(syncedVideo, capturedAt)
          case None => ScheduledVideoRemoval(videoId, capturedAt)
        }
      }
    }

  /** Never fails: if the fallback stays unreachable, a reconcile is flagged to repair it later. */
  def publish(videoIds: List[String]): F[Unit] =
    messagesFor(videoIds)
      .flatMap(sendWithRetries)
      .handleErrorWith { error =>
        logger.error[F](s"Fallback sync of ${videoIds.size} videos failed; flagging a reconcile", error) *>
          coordination.markReconcileNeeded
      }

  def pipeline[A](subscriber: Subscriber[F, A], groupId: String)(videoId: A => String): Stream[F, Unit] =
    subscriber
      .subscribe(groupId)
      .groupWithin(maxBatchSize, window)
      .evalMap { chunk =>
        publish(chunk.toList.map(value => videoId(subscriber.extractValue(value)))) *> subscriber.commit(chunk)
      }

  private def sendWithRetries(messages: List[MainToFallbackMessage]): F[Unit] =
    retryDelays.foldLeft(transport.send(messages)) { (attempt, delay) =>
      attempt.handleErrorWith(_ => Temporal[F].sleep(delay) *> transport.send(messages))
    }
}
```

(`Logger[A: ClassTag]` needs a concrete class tag; if `Logger[FallbackSyncPublisher[F, T]]` does not compile, use
`Logger[FallbackSyncPublisher[Any, Any]]` or a companion `object` holding the logger.)

- [ ] **Step 4: Run the tests to verify they pass**

Run: `sbt "api/testOnly com.ruchij.api.services.fallback.*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add api
git commit -m "Add the fallback sync publisher with DB reads, retries and a reconcile flag.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Reconcile

**Files:**
- Create: `api/src/main/scala/com/ruchij/api/services/fallback/ReconcileDiff.scala`
- Create: `api/src/main/scala/com/ruchij/api/services/fallback/FallbackReconciler.scala`
- Create: `api/src/test/scala/com/ruchij/api/services/fallback/ReconcileDiffSpec.scala`
- Create: `api/src/test/scala/com/ruchij/api/services/fallback/FallbackReconcilerSpec.scala`

**Interfaces:**
- Consumes: `FallbackManifestReader`, `ManifestEntry` (Task 4); `FallbackSyncDao`, `FallbackSyncCoordination`,
  stubs (Task 5).
- Produces:
  - `ReconcileDiff(upserts: List[ScheduledVideoUpsert], removedVideoIds: List[String])` and
    `ReconcileDiff.compute(manifest: Map[String, ManifestEntry], current: List[ScheduledVideoUpsert]): ReconcileDiff`
  - `ReconcileSummary(upserts: Int, removals: Int)`
  - `class FallbackReconciler[F[_]: Temporal: Clock, T[_]](manifestReader: FallbackManifestReader[F],
    fallbackSyncDao: FallbackSyncDao[T], transport: FallbackSyncTransport[F], coordination:
    FallbackSyncCoordination[F], instanceId: String)(implicit transaction: T ~> F)` with
    `reconcile: F[Option[ReconcileSummary]]` and `run(interval: FiniteDuration = 24.hours, flagCheckInterval:
    FiniteDuration = 5.minutes): Stream[F, Unit]`

- [ ] **Step 1: Write the failing tests**

`api/src/test/scala/com/ruchij/api/services/fallback/ReconcileDiffSpec.scala`:

```scala
package com.ruchij.api.services.fallback

import com.ruchij.api.services.fallback.FallbackSyncTestData.fixtureUpsert
import com.ruchij.api.services.fallback.aws.ManifestEntry
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.time.Instant

class ReconcileDiffSpec extends AnyFlatSpec with Matchers {
  private def upsert(videoId: String, hash: String) = fixtureUpsert.copy(videoId = videoId, hash = hash)
  private def entry(hash: String) = ManifestEntry(hash, Instant.EPOCH)

  "ReconcileDiff" should "upsert missing and changed videos, remove extra ones and skip matching ones" in {
    val diff =
      ReconcileDiff.compute(
        manifest = Map("same" -> entry("h1"), "changed" -> entry("old"), "extra" -> entry("h3")),
        current = List(upsert("same", "h1"), upsert("changed", "new"), upsert("missing", "h4"))
      )

    diff.upserts.map(_.videoId).sorted mustBe List("changed", "missing")
    diff.removedVideoIds mustBe List("extra")
  }

  it should "do nothing when both sides match" in {
    ReconcileDiff.compute(Map("a" -> entry("h")), List(upsert("a", "h"))) mustBe ReconcileDiff(Nil, Nil)
  }
}
```

`api/src/test/scala/com/ruchij/api/services/fallback/FallbackReconcilerSpec.scala`:

```scala
package com.ruchij.api.services.fallback

import cats.effect.IO
import cats.effect.kernel.Ref
import com.ruchij.api.services.fallback.FallbackSyncStubs._
import com.ruchij.api.services.fallback.FallbackSyncTestData.{capturedAt, scheduledVideoDownload}
import com.ruchij.api.services.fallback.aws.{FallbackManifestReader, ManifestEntry}
import com.ruchij.api.services.fallback.models.{ScheduledVideoRemoval, ScheduledVideoUpsert}
import com.ruchij.core.kv.InMemoryKeyValueStore
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.test.Providers
import com.ruchij.core.types.Clock
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.time.Instant

class FallbackReconcilerSpec extends AnyFlatSpec with Matchers {
  implicit val clock: Clock[IO] = Providers.stubClock[IO](capturedAt)

  private def manifestOf(entries: (String, ManifestEntry)*): FallbackManifestReader[IO] =
    new FallbackManifestReader[IO] {
      override val manifest: IO[Map[String, ManifestEntry]] = IO.pure(entries.toMap)
    }

  "FallbackReconciler" should "send upserts for drift and removals for videos gone from the DB" in runIO {
    val video1 = SyncedVideo(scheduledVideoDownload("video-1"), List("user-1"))
    val video2 = SyncedVideo(scheduledVideoDownload("video-2"), List("user-1"))
    val inSync = ScheduledVideoUpserts.from(video1, capturedAt)

    for {
      dao <- StubFallbackSyncDao(video1, video2)
      transport <- RecordingTransport()
      coordination = new FallbackSyncCoordination[IO](new InMemoryKeyValueStore[IO])
      _ <- coordination.markReconcileNeeded
      reconciler = new FallbackReconciler[IO, IO](
        manifestOf("video-1" -> ManifestEntry(inSync.hash, Instant.EPOCH), "gone" -> ManifestEntry("h", Instant.EPOCH)),
        dao,
        transport,
        coordination,
        "instance-a"
      )
      summary <- reconciler.reconcile
      sent <- transport.messages
      flagged <- coordination.isReconcileNeeded
    } yield {
      summary mustBe Some(ReconcileSummary(upserts = 1, removals = 1))
      sent.collect { case upsert: ScheduledVideoUpsert => upsert.videoId } mustBe List("video-2")
      sent.collect { case removal: ScheduledVideoRemoval => removal.videoId } mustBe List("gone")
      flagged mustBe false
    }
  }

  it should "not remove a video that the DB listing missed but that still exists" in runIO {
    val video1 = SyncedVideo(scheduledVideoDownload("video-1"), List("user-1"))

    for {
      dao <- StubFallbackSyncDao(video1)
      // Simulates a paging skip: findAll misses the video while findById still finds it.
      skippingDao = new FallbackSyncDao[IO] {
        override def findById(videoId: String): IO[Option[SyncedVideo]] = dao.findById(videoId)
        override val findAll: IO[List[SyncedVideo]] = IO.pure(Nil)
      }
      transport <- RecordingTransport()
      coordination = new FallbackSyncCoordination[IO](new InMemoryKeyValueStore[IO])
      reconciler = new FallbackReconciler[IO, IO](
        manifestOf("video-1" -> ManifestEntry("stale", Instant.EPOCH)),
        skippingDao,
        transport,
        coordination,
        "instance-a"
      )
      _ <- reconciler.reconcile
      sent <- transport.messages
    } yield sent.collect { case removal: ScheduledVideoRemoval => removal } mustBe empty
  }

  it should "read the manifest before the database" in runIO {
    for {
      order <- Ref.of[IO, List[String]](Nil)
      manifestReader = new FallbackManifestReader[IO] {
        override val manifest: IO[Map[String, ManifestEntry]] = order.update(_ :+ "manifest").as(Map.empty)
      }
      dao = new FallbackSyncDao[IO] {
        override def findById(videoId: String): IO[Option[SyncedVideo]] = IO.pure(None)
        override val findAll: IO[List[SyncedVideo]] = order.update(_ :+ "database").as(Nil)
      }
      transport <- RecordingTransport()
      coordination = new FallbackSyncCoordination[IO](new InMemoryKeyValueStore[IO])
      _ <- new FallbackReconciler[IO, IO](manifestReader, dao, transport, coordination, "instance-a").reconcile
      calls <- order.get
    } yield calls mustBe List("manifest", "database")
  }

  it should "skip when another instance holds the lock" in runIO {
    for {
      dao <- StubFallbackSyncDao()
      transport <- RecordingTransport()
      coordination = new FallbackSyncCoordination[IO](new InMemoryKeyValueStore[IO])
      reconciler = new FallbackReconciler[IO, IO](manifestOf(), dao, transport, coordination, "instance-a")
      result <- coordination.withReconcileLock("instance-b")(reconciler.reconcile)
    } yield result mustBe Some(None)
  }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `sbt "api/testOnly com.ruchij.api.services.fallback.*Reconcile*"`
Expected: compilation FAIL — `not found: value ReconcileDiff`.

- [ ] **Step 3: Implement**

`api/src/main/scala/com/ruchij/api/services/fallback/ReconcileDiff.scala`:

```scala
package com.ruchij.api.services.fallback

import com.ruchij.api.services.fallback.aws.ManifestEntry
import com.ruchij.api.services.fallback.models.ScheduledVideoUpsert

final case class ReconcileDiff(upserts: List[ScheduledVideoUpsert], removedVideoIds: List[String])

object ReconcileDiff {
  def compute(manifest: Map[String, ManifestEntry], current: List[ScheduledVideoUpsert]): ReconcileDiff = {
    val upserts = current.filterNot(upsert => manifest.get(upsert.videoId).map(_.hash).contains(upsert.hash))
    val currentVideoIds = current.map(_.videoId).toSet

    ReconcileDiff(upserts, manifest.keys.filterNot(currentVideoIds.contains).toList.sorted)
  }
}
```

`api/src/main/scala/com/ruchij/api/services/fallback/FallbackReconciler.scala`:

```scala
package com.ruchij.api.services.fallback

import cats.effect.Temporal
import cats.implicits._
import cats.~>
import com.ruchij.api.services.fallback.aws.{FallbackManifestReader, FallbackSyncTransport}
import com.ruchij.api.services.fallback.models.{MainToFallbackMessage, ScheduledVideoRemoval}
import com.ruchij.core.logging.Logger
import com.ruchij.core.types.Clock
import fs2.Stream

import java.time.Instant
import scala.concurrent.duration._

final case class ReconcileSummary(upserts: Int, removals: Int)

class FallbackReconciler[F[_]: Temporal: Clock, T[_]](
  manifestReader: FallbackManifestReader[F],
  fallbackSyncDao: FallbackSyncDao[T],
  transport: FallbackSyncTransport[F],
  coordination: FallbackSyncCoordination[F],
  instanceId: String
)(implicit transaction: T ~> F) {
  private val logger = Logger[FallbackReconciler[F, T]]

  /** None when another instance holds the reconcile lock. */
  val reconcile: F[Option[ReconcileSummary]] =
    coordination.withReconcileLock(instanceId) {
      for {
        // The manifest must be read before the DB: a video synced between the two reads then shows up as an
        // extra (harmless) upsert instead of being wrongly removed.
        manifest <- manifestReader.manifest
        capturedAt <- Clock[F].timestamp
        videos <- transaction(fallbackSyncDao.findAll)
        diff = ReconcileDiff.compute(manifest, videos.map(ScheduledVideoUpserts.from(_, capturedAt)))
        removals <- confirmedRemovals(diff.removedVideoIds, capturedAt)
        _ <- transport.send(diff.upserts ++ removals)
        _ <- coordination.clearReconcileNeeded
        summary = ReconcileSummary(diff.upserts.size, removals.count(_.isInstanceOf[ScheduledVideoRemoval]))
        _ <- logger.info[F](s"Fallback reconcile sent ${summary.upserts} upserts and ${summary.removals} removals")
      } yield summary
    }

  def run(interval: FiniteDuration = 24.hours, flagCheckInterval: FiniteDuration = 5.minutes): Stream[F, Unit] = {
    val scheduled = Stream.eval(reconcileSafely) ++ Stream.awakeEvery[F](interval).evalMap(_ => reconcileSafely)
    val flagged =
      Stream
        .awakeEvery[F](flagCheckInterval)
        .evalMap(_ => coordination.isReconcileNeeded.ifM(reconcileSafely, Temporal[F].unit))

    scheduled.merge(flagged)
  }

  private val reconcileSafely: F[Unit] =
    reconcile.void.handleErrorWith { error =>
      logger.error[F]("Fallback reconcile failed; it will be retried", error) *> coordination.markReconcileNeeded
    }

  /** Re-checks each candidate: paging through the DB while rows change can miss a video that still exists. */
  private def confirmedRemovals(videoIds: List[String], capturedAt: Instant): F[List[MainToFallbackMessage]] =
    videoIds.traverse { videoId =>
      transaction(fallbackSyncDao.findById(videoId)).map[MainToFallbackMessage] {
        case Some(syncedVideo) => ScheduledVideoUpserts.from(syncedVideo, capturedAt)
        case None => ScheduledVideoRemoval(videoId, capturedAt)
      }
    }
}
```

Note: when the re-check finds the video, it is sent as an upsert, so `ReconcileSummary.upserts` counts only the
diff's upserts while the re-checked ones are sent but not counted as removals. The first reconciler test expects
`ReconcileSummary(upserts = 1, removals = 1)`, which this satisfies.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `sbt "api/testOnly com.ruchij.api.services.fallback.*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add api
git commit -m "Reconcile the fallback copy by comparing DynamoDB hashes with the database.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Request consumer

**Files:**
- Create: `api/src/main/scala/com/ruchij/api/services/fallback/FallbackRequestConsumer.scala`
- Create: `api/src/test/scala/com/ruchij/api/services/fallback/FallbackRequestConsumerSpec.scala`

**Interfaces:**
- Consumes: `FallbackRequestQueue`, `ReceivedMessage`, `FallbackSyncTransport` (Task 4); `FallbackSyncDao`
  (Task 5); `ApiSchedulingService[F]`; `UserDao[T]`; `SyncJson.decodeScheduleRequest` (Task 2).
- Produces: `class FallbackRequestConsumer[F[_]: Temporal: Clock, T[_]](requestQueue: FallbackRequestQueue[F],
  schedulingService: ApiSchedulingService[F], userDao: UserDao[T], fallbackSyncDao: FallbackSyncDao[T],
  transport: FallbackSyncTransport[F], pollInterval: FiniteDuration = 60.seconds, maxReceiveCount: Int = 5)(implicit
  transaction: T ~> F)` with `handle(message: ReceivedMessage): F[Unit]`, `drain: F[Unit]`, `run: Stream[F, Unit]`

- [ ] **Step 1: Write the failing tests**

`api/src/test/scala/com/ruchij/api/services/fallback/FallbackRequestConsumerSpec.scala`:

```scala
package com.ruchij.api.services.fallback

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.kernel.Ref
import com.ruchij.api.daos.user.UserDao
import com.ruchij.api.daos.user.models.{Email, Role, User}
import com.ruchij.api.services.fallback.FallbackSyncStubs._
import com.ruchij.api.services.fallback.FallbackSyncTestData.{capturedAt, scheduledVideoDownload}
import com.ruchij.api.services.fallback.aws.{FallbackRequestQueue, ReceivedMessage}
import com.ruchij.api.services.fallback.models._
import com.ruchij.api.services.scheduling.ApiSchedulingService
import com.ruchij.api.services.scheduling.models.ScheduledVideoResult
import com.ruchij.core.daos.scheduling.models.{RangeValue, ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.daos.videometadata.models.VideoSite
import com.ruchij.core.daos.workers.models.WorkerStatus
import com.ruchij.core.exceptions.{ExternalServiceException, ValidationException}
import com.ruchij.core.services.models.{Order, SortBy}
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.test.Providers
import com.ruchij.core.types.Clock
import org.http4s.Uri
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

class FallbackRequestConsumerSpec extends AnyFlatSpec with Matchers {
  implicit val clock: Clock[IO] = Providers.stubClock[IO](capturedAt)

  private val request = ScheduleRequest("request-1", "user-1", "https://example.com/video", capturedAt)

  private def message(body: String, receiveCount: Int = 1) = ReceivedMessage(body, "receipt-1", receiveCount)

  private def body(scheduleRequest: ScheduleRequest): String =
    SyncJson.scheduleRequestEncoder(scheduleRequest).noSpaces

  private final class StubQueue(deleted: Ref[IO, List[String]]) extends FallbackRequestQueue[IO] {
    override val receive: IO[List[ReceivedMessage]] = IO.pure(Nil)
    override def delete(receiptHandle: String): IO[Unit] = deleted.update(_ :+ receiptHandle)
    val deletedHandles: IO[List[String]] = deleted.get
  }

  private def notUsed[A]: IO[A] = IO.raiseError(new NotImplementedError("not used by the consumer"))

  private def userDao(exists: Boolean): UserDao[IO] =
    new UserDao[IO] {
      override def insert(user: User): IO[Int] = notUsed
      override def findByEmail(email: Email): IO[Option[User]] = notUsed
      override def deleteById(userId: String): IO[Int] = notUsed

      override def findById(userId: String): IO[Option[User]] =
        IO.pure {
          if (exists) Some(User(userId, capturedAt, "Test", "User", Email(s"$userId@test.com"), Role.User))
          else None
        }
    }

  private def schedulingService(outcome: IO[ScheduledVideoResult]): ApiSchedulingService[IO] =
    new ApiSchedulingService[IO] {
      override def schedule(uri: Uri, userId: String): IO[ScheduledVideoResult] = outcome

      override def search(
        term: Option[String],
        videoUrls: Option[NonEmptyList[Uri]],
        durationRange: RangeValue[FiniteDuration],
        sizeRange: RangeValue[Long],
        pageNumber: Int,
        pageSize: Int,
        sortBy: SortBy,
        order: Order,
        schedulingStatuses: Option[NonEmptyList[SchedulingStatus]],
        videoSites: Option[NonEmptyList[VideoSite]],
        maybeUserId: Option[String]
      ): IO[Seq[ScheduledVideoDownload]] = notUsed

      override def retryFailed(maybeUserId: Option[String]): IO[Seq[ScheduledVideoDownload]] = notUsed

      override def updateSchedulingStatus(
        id: String,
        status: SchedulingStatus,
        maybeUserId: Option[String]
      ): IO[ScheduledVideoDownload] = notUsed

      override def getById(id: String, maybeUserId: Option[String]): IO[ScheduledVideoDownload] = notUsed

      override def updateWorkerStatus(workerStatus: WorkerStatus): IO[Unit] = notUsed

      override val getWorkerStatus: IO[WorkerStatus] = notUsed

      override def updateDownloadProgress(
        id: String,
        timestamp: Instant,
        downloadedBytes: Long
      ): IO[ScheduledVideoDownload] = notUsed

      override def deleteById(id: String, maybeUserId: Option[String]): IO[ScheduledVideoDownload] = notUsed
    }

  private def run(
    schedule: IO[ScheduledVideoResult],
    userExists: Boolean,
    received: ReceivedMessage
  ): IO[(List[MainToFallbackMessage], List[String])] =
    for {
      dao <- StubFallbackSyncDao(SyncedVideo(scheduledVideoDownload("video-1"), List("user-1")))
      transport <- RecordingTransport()
      queue <- Ref.of[IO, List[String]](Nil).map(new StubQueue(_))
      consumer =
        new FallbackRequestConsumer[IO, IO](queue, schedulingService(schedule), userDao(userExists), dao, transport)
      _ <- consumer.handle(received)
      sent <- transport.messages
      deleted <- queue.deletedHandles
    } yield (sent, deleted)

  private val scheduled = IO.pure(ScheduledVideoResult.NewlyScheduled(scheduledVideoDownload("video-1")))

  "FallbackRequestConsumer" should "reply Scheduled with the video's state and delete the message" in runIO {
    run(scheduled, userExists = true, message(body(request))).map {
      case (sent, deleted) =>
        sent match {
          case List(RequestResolved("request-1", "user-1", ResolutionOutcome.Scheduled(upsert))) =>
            upsert.videoId mustBe "video-1"
          case other => fail(s"Unexpected messages: $other")
        }
        deleted mustBe List("receipt-1")
    }
  }

  it should "reject an unknown user without scheduling" in runIO {
    run(IO.raiseError(new AssertionError("must not schedule")), userExists = false, message(body(request))).map {
      case (sent, deleted) =>
        sent mustBe List(RequestResolved("request-1", "user-1", ResolutionOutcome.Rejected("Unknown user: user-1")))
        deleted mustBe List("receipt-1")
    }
  }

  it should "reject a URL that cannot be parsed without scheduling" in runIO {
    val badUrl = request.copy(url = "https://exa mple.com/%zz")

    run(IO.raiseError(new AssertionError("must not schedule")), userExists = true, message(body(badUrl))).map {
      case (sent, deleted) =>
        sent.collect { case RequestResolved(_, _, ResolutionOutcome.Rejected(reason)) => reason } must have size 1
        deleted mustBe List("receipt-1")
    }
  }

  it should "reject permanent scheduling failures" in runIO {
    run(IO.raiseError(ValidationException("Unable infer video site")), userExists = true, message(body(request))).map {
      case (sent, deleted) =>
        sent mustBe List(
          RequestResolved("request-1", "user-1", ResolutionOutcome.Rejected("Unable infer video site"))
        )
        deleted mustBe List("receipt-1")
    }
  }

  it should "leave transient failures on the queue until the final attempt" in runIO {
    val transient = IO.raiseError(ExternalServiceException("metadata timeout"))

    for {
      early <- run(transient, userExists = true, message(body(request), receiveCount = 2))
      last <- run(transient, userExists = true, message(body(request), receiveCount = 5))
    } yield {
      early mustBe ((Nil, Nil))
      last._1.collect { case RequestResolved(_, _, ResolutionOutcome.Rejected(reason)) => reason }.head must
        include("after 5 attempts")
      last._2 mustBe List("receipt-1")
    }
  }

  it should "leave an undecodable message for the dead-letter queue" in runIO {
    run(scheduled, userExists = true, message("{not json")).map { _ mustBe ((Nil, Nil)) }
  }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `sbt "api/testOnly com.ruchij.api.services.fallback.FallbackRequestConsumerSpec"`
Expected: compilation FAIL — `not found: type FallbackRequestConsumer`.

- [ ] **Step 3: Implement**

`api/src/main/scala/com/ruchij/api/services/fallback/FallbackRequestConsumer.scala`:

```scala
package com.ruchij.api.services.fallback

import cats.effect.Temporal
import cats.implicits._
import cats.~>
import com.ruchij.api.daos.user.UserDao
import com.ruchij.api.services.fallback.aws.{FallbackRequestQueue, FallbackSyncTransport, ReceivedMessage}
import com.ruchij.api.services.fallback.models.{RequestResolved, ResolutionOutcome, ScheduleRequest, SyncJson}
import com.ruchij.api.services.scheduling.ApiSchedulingService
import com.ruchij.core.exceptions.{ResourceNotFoundException, UnsupportedVideoUrlException, ValidationException}
import com.ruchij.core.logging.Logger
import com.ruchij.core.types.Clock
import fs2.Stream
import org.http4s.Uri

import scala.concurrent.duration._

class FallbackRequestConsumer[F[_]: Temporal: Clock, T[_]](
  requestQueue: FallbackRequestQueue[F],
  schedulingService: ApiSchedulingService[F],
  userDao: UserDao[T],
  fallbackSyncDao: FallbackSyncDao[T],
  transport: FallbackSyncTransport[F],
  pollInterval: FiniteDuration = 60.seconds,
  maxReceiveCount: Int = 5
)(implicit transaction: T ~> F) {
  private val logger = Logger[FallbackRequestConsumer[F, T]]

  val run: Stream[F, Unit] =
    Stream
      .fixedRateStartImmediately[F](pollInterval)
      .evalMap { _ =>
        drain.handleErrorWith(error => logger.error[F]("Polling fallback schedule requests failed", error))
      }

  lazy val drain: F[Unit] =
    requestQueue.receive.flatMap { messages =>
      if (messages.isEmpty) Temporal[F].unit
      else messages.traverse_(message => handle(message).handleErrorWith(logFailure(message))) *> drain
    }

  def handle(message: ReceivedMessage): F[Unit] =
    SyncJson.decodeScheduleRequest(message.body) match {
      case Left(error) =>
        // Without a request id no reply is possible; leaving it lets SQS dead-letter it and raise the alarm.
        logger.warn[F] {
          s"Leaving an undecodable fallback schedule request for the dead-letter queue: ${error.getMessage}"
        }

      case Right(request) =>
        resolve(request, message.receiveCount).flatMap {
          case Some(outcome) =>
            transport.send(List(RequestResolved(request.requestId, request.userId, outcome))) *>
              requestQueue.delete(message.receiptHandle)

          case None => Temporal[F].unit
        }
    }

  /** None means "transient, leave it on the queue for SQS to redeliver". */
  private def resolve(request: ScheduleRequest, receiveCount: Int): F[Option[ResolutionOutcome]] =
    transaction(userDao.findById(request.userId)).flatMap {
      case None => rejected(s"Unknown user: ${request.userId}")

      case Some(_) =>
        Uri.fromString(request.url) match {
          case Left(_) => rejected(s"Invalid URL: ${request.url}")

          case Right(uri) =>
            schedulingService.schedule(uri, request.userId).attempt.flatMap {
              case Right(result) =>
                scheduledOutcome(result.scheduledVideoDownload.videoMetadata.id).map(Option(_))

              case Left(error) if isPermanent(error) => rejected(errorMessage(error))

              case Left(error) if receiveCount >= maxReceiveCount =>
                rejected(s"Unable to schedule the video after $receiveCount attempts: ${errorMessage(error)}")

              case Left(error) =>
                logger
                  .warn[F](s"Transient failure scheduling request ${request.requestId}: ${errorMessage(error)}")
                  .as(Option.empty[ResolutionOutcome])
            }
        }
    }

  private def scheduledOutcome(videoId: String): F[ResolutionOutcome] =
    Clock[F].timestamp.flatMap { capturedAt =>
      transaction(fallbackSyncDao.findById(videoId)).flatMap {
        case Some(syncedVideo) =>
          Temporal[F].pure[ResolutionOutcome] {
            ResolutionOutcome.Scheduled(ScheduledVideoUpserts.from(syncedVideo, capturedAt))
          }

        case None => Temporal[F].raiseError(new IllegalStateException(s"Scheduled video $videoId was not found"))
      }
    }

  private def rejected(reason: String): F[Option[ResolutionOutcome]] =
    Temporal[F].pure(Option(ResolutionOutcome.Rejected(reason)))

  private def isPermanent(error: Throwable): Boolean =
    error match {
      case _: ValidationException | _: UnsupportedVideoUrlException | _: ResourceNotFoundException => true
      case _ => false
    }

  private def errorMessage(error: Throwable): String = Option(error.getMessage).getOrElse(error.getClass.getSimpleName)

  private def logFailure(message: ReceivedMessage)(error: Throwable): F[Unit] =
    logger.error[F](s"Handling fallback schedule request (receive ${message.receiveCount}) failed", error)
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `sbt "api/testOnly com.ruchij.api.services.fallback.*"`
Expected: PASS. (If `Uri.fromString` accepts the test's "bad" URL, pick another it rejects, e.g. `"http://[::1"`.)

- [ ] **Step 5: Commit**

```bash
git add api
git commit -m "Process schedule requests from the fallback queue and reply with the outcome.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Wiring into `ApiApp`

**Files:**
- Create: `api/src/main/scala/com/ruchij/api/services/fallback/FallbackSync.scala`
- Modify: `api/src/main/scala/com/ruchij/api/models/ApiMessageBrokers.scala`
- Modify: `api/src/main/scala/com/ruchij/api/ApiApp.scala`

**Interfaces:**
- Consumes: everything above.
- Produces:
  - `final case class FallbackSyncResources[F[_]](settings: FallbackSyncSettings, awsClients: FallbackSyncAwsClients,
    fallbackSyncRequestPubSub: PubSub[F, FallbackSyncRequest])`
  - `FallbackSync.stream[F[_]: Async: Clock](resources: FallbackSyncResources[F], keyValueStore: KeyValueStore[F],
    schedulingService: ApiSchedulingService[F], scheduledVideoDownloadSubscriber: Subscriber[F,
    ScheduledVideoDownload], instanceId: String)(implicit transaction: ConnectionIO ~> F): Stream[F, Unit]`
  - `ApiMessageBrokers` gains `fallbackSyncRequestPublisher: Publisher[F, FallbackSyncRequest]`

- [ ] **Step 1: Implement `FallbackSync`**

```scala
package com.ruchij.api.services.fallback

import cats.effect.Async
import cats.~>
import com.ruchij.api.config.FallbackSyncSettings
import com.ruchij.api.daos.user.DoobieUserDao
import com.ruchij.api.services.fallback.aws._
import com.ruchij.api.services.fallback.models.FallbackSyncRequest
import com.ruchij.api.services.scheduling.ApiSchedulingService
import com.ruchij.core.daos.permission.DoobieVideoPermissionDao
import com.ruchij.core.daos.scheduling.DoobieSchedulingDao
import com.ruchij.core.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.core.kv.KeyValueStore
import com.ruchij.core.messaging.{PubSub, Subscriber}
import com.ruchij.core.types.Clock
import doobie.free.connection.ConnectionIO
import fs2.Stream

final case class FallbackSyncResources[F[_]](
  settings: FallbackSyncSettings,
  awsClients: FallbackSyncAwsClients,
  fallbackSyncRequestPubSub: PubSub[F, FallbackSyncRequest]
)

object FallbackSync {
  // Shared by every API instance, so each message is handled by one of them.
  val SubscriberGroupId = "fallback-sync"

  def stream[F[_]: Async: Clock](
    resources: FallbackSyncResources[F],
    keyValueStore: KeyValueStore[F],
    schedulingService: ApiSchedulingService[F],
    scheduledVideoDownloadSubscriber: Subscriber[F, ScheduledVideoDownload],
    instanceId: String
  )(implicit transaction: ConnectionIO ~> F): Stream[F, Unit] = {
    val settings = resources.settings
    val fallbackSyncDao = new DoobieFallbackSyncDao(DoobieSchedulingDao, DoobieVideoPermissionDao)
    val transport = new SqsFallbackSyncTransport[F](resources.awsClients.sqs, settings.mainToFallbackQueueUrl)
    val coordination = new FallbackSyncCoordination[F](keyValueStore)

    val publisher = new FallbackSyncPublisher[F, ConnectionIO](fallbackSyncDao, transport, coordination)
    val reconciler =
      new FallbackReconciler[F, ConnectionIO](
        new DynamoDbFallbackManifestReader[F](resources.awsClients.dynamoDb, settings.tableName),
        fallbackSyncDao,
        transport,
        coordination,
        instanceId
      )
    val consumer =
      new FallbackRequestConsumer[F, ConnectionIO](
        new SqsFallbackRequestQueue[F](resources.awsClients.sqs, settings.fallbackToMainQueueUrl),
        schedulingService,
        DoobieUserDao,
        fallbackSyncDao,
        transport
      )

    Stream(
      publisher.pipeline(scheduledVideoDownloadSubscriber, SubscriberGroupId)(_.videoMetadata.id),
      publisher.pipeline(resources.fallbackSyncRequestPubSub, SubscriberGroupId)(_.videoId),
      reconciler.run(),
      consumer.run
    ).parJoinUnbounded
  }
}
```

- [ ] **Step 2: Wire it into `ApiApp`**

1. `ApiMessageBrokers`: add the field `fallbackSyncRequestPublisher: Publisher[F, FallbackSyncRequest]` (last).
2. `ApiApp.create`, after `scanVideoCommandPublisher <- ...`:

```scala
      fallbackSyncSettings <- Resource.eval(apiServiceConfiguration.fallbackSyncConfiguration.settings.liftTo[F])
      fallbackSyncResources <- fallbackSyncSettings.traverse { settings =>
        for {
          awsClients <- FallbackSyncAwsClients.create[F](settings)
          fallbackSyncRequestPubSub <- pubSubProvider.pubSub[FallbackSyncRequest]
        } yield FallbackSyncResources(settings, awsClients, fallbackSyncRequestPubSub)
      }
```

   and pass `fallbackSyncResources.fold[Publisher[F, FallbackSyncRequest]](new NoOpPublisher[F, FallbackSyncRequest])(
   _.fallbackSyncRequestPubSub)` as the new last `ApiMessageBrokers` argument. Pass `fallbackSyncResources` to
   `program` as a new last parameter `fallbackSyncResources: Option[FallbackSyncResources[F]]`.
3. `ApiApp.program`: use `messageBrokers.fallbackSyncRequestPublisher` for the `ApiSchedulingServiceImpl` argument
   added in Task 3 (replacing the `NoOpPublisher`), and after `_ <- backgroundService.run` add:

```scala
      _ <- fallbackSyncResources.traverse_ { resources =>
        Concurrent[F].start {
          FallbackSync
            .stream[F](
              resources,
              keyValueStore,
              schedulingService,
              messageBrokers.scheduledVideoDownloadPubSub,
              instanceId
            )
            .compile
            .drain
        }
      }
```

   (`Concurrent` from `cats.effect`, matching how `backgroundService.run` starts its fiber.)

- [ ] **Step 3: Verify**

Run: `sbt compile` then `sbt "api/testOnly com.ruchij.api.ApiAppSpec"` (sync disabled there, so the app must behave
exactly as before).
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add api
git commit -m "Start fallback sync in the API when FALLBACK_SYNC_ENABLED is set.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Final verification and rollout notes

**Files:** none (or `README.md` if the notes below belong there — ask the user).

- [ ] **Step 1: Full test run**

Run: `sbt testFull`
Expected: every suite passes. Report the summary line (suites / tests / failures) to the user.

- [ ] **Step 2: Hand the rollout steps to the user (do not run them)**

- Create access keys for the phase 1 stack's `MainSideSyncUserName` output and set on every API instance:
  `FALLBACK_SYNC_ENABLED=true`, `FALLBACK_SYNC_MAIN_TO_FALLBACK_QUEUE_URL`, `FALLBACK_SYNC_FALLBACK_TO_MAIN_QUEUE_URL`,
  `FALLBACK_SYNC_TABLE_NAME` (stack outputs), `FALLBACK_SYNC_AWS_REGION=ap-southeast-2`, plus `AWS_ACCESS_KEY_ID` /
  `AWS_SECRET_ACCESS_KEY`.
- With `PUBSUB_TYPE=Kafka`, make sure the `<prefix>-fallback-sync-requests` topic exists if the broker does not
  auto-create topics.
- The first start runs a reconcile, which is the initial backfill.
