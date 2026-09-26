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
      capturedAt = Instant.parse("2026-09-26T08:15:30.123456Z"),
      hash = "0f1e2d3c4b5a6978",
      userIds = List("user-1", "user-2"),
      url = "https://www.youtube.com/watch?v=abc123",
      videoSite = "YouTube",
      title = "Sample video",
      durationMs = 212000,
      sizeBytes = 48234567,
      status = "Completed",
      scheduledAt = Instant.parse("2026-09-25T21:04:11Z"),
      completedAt = Some(Instant.parse("2026-09-25T21:09:42.500250Z"))
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
