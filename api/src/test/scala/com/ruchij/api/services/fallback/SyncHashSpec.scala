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
    // One change per field SyncHash.of covers, so leaving any of them out of the hash fails this test.
    val changes = List(
      fixtureUpsert.copy(videoId = "youtube-other"),
      fixtureUpsert.copy(url = "https://www.youtube.com/watch?v=other"),
      fixtureUpsert.copy(videoSite = "Vimeo"),
      fixtureUpsert.copy(title = "Other title"),
      fixtureUpsert.copy(durationMs = 1),
      fixtureUpsert.copy(sizeBytes = 1),
      fixtureUpsert.copy(status = "Queued"),
      fixtureUpsert.copy(scheduledAt = Instant.parse("2026-09-25T21:04:12.000Z")),
      fixtureUpsert.copy(completedAt = None),
      fixtureUpsert.copy(completedAt = Some(Instant.parse("2026-09-25T21:09:43.500Z"))),
      // Timestamps enter the hash at microsecond precision, like the messages
      fixtureUpsert.copy(completedAt = Some(Instant.parse("2026-09-25T21:09:42.500251Z"))),
      fixtureUpsert.copy(userIds = List("user-1"))
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
