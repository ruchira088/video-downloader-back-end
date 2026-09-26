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
    val removal = ScheduledVideoRemoval("youtube-1a2b3c4d5e6f", Instant.parse("2026-09-26T08:20:00.000042Z"))

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
        Instant.parse("2026-09-26T08:15:00.654321Z")
      )
    )
  }

  it should "reject a message of another type" in {
    SyncJson.decodeScheduleRequest(read("scheduled-video-removal.json")).isLeft mustBe true
  }

  it should "always write exactly six fractional digits, padding zeros and truncating nanoseconds" in {
    SyncJson.formatTimestamp(Instant.parse("2026-09-26T08:20:00Z")) mustBe "2026-09-26T08:20:00.000000Z"
    SyncJson.formatTimestamp(Instant.parse("2026-09-26T08:20:00.123456789Z")) mustBe "2026-09-26T08:20:00.123456Z"
  }

  it should "reject request timestamps in any other shape" in {
    val requestJson = json("schedule-request.json")

    List("2026-09-26T08:15:00Z", "2026-09-26T08:15:00.654Z", "2026-09-26T08:15:00.654321+00:00").foreach { value =>
      val body = requestJson.mapObject(_.add("requestedAt", io.circe.Json.fromString(value))).noSpaces

      SyncJson.decodeScheduleRequest(body).isLeft mustBe true
    }
  }

  it should "decode both RequestResolved contract fixtures back into what they encode" in {
    List("request-resolved-scheduled.json", "request-resolved-rejected.json").foreach { fixture =>
      val decoded = SyncJson.decodeRequestResolved(read(fixture))

      decoded.map(reply => canonical(parse(SyncJson.encode(reply)).fold(throw _, identity))) mustBe
        Right(canonical(json(fixture)))
    }
  }
}
