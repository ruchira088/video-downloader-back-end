package com.ruchij.core.messaging

import com.ruchij.core.commands.ScanVideosCommand
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.scheduling.models.ScheduledVideoDownload.ErrorInfo
import com.ruchij.core.daos.scheduling.models.{ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.daos.videometadata.models.{CustomVideoSite, VideoMetadata}
import com.ruchij.core.daos.workers.models.WorkerStatus
import com.ruchij.core.messaging.models.{HttpMetric, VideoWatchMetric}
import com.ruchij.core.services.scheduling.models.{DownloadProgress, WorkerStatusUpdate}
import com.ruchij.core.types.TimeUtils
import io.circe.parser.decode
import org.http4s.implicits.http4sLiteralsSyntax
import org.http4s.{MediaType, Method, Status}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

// The Redis and Doobie pub-sub backends serialise messages with these JSON codecs, so each codec must decode
// exactly what it encodes.
class MessagingTopicJsonCodecSpec extends AnyFlatSpec with Matchers {

  private val timestamp = TimeUtils.instantOf(2024, 3, 10, 8, 15)

  private def roundTrip[A](value: A)(implicit messagingTopic: MessagingTopic[A]): Either[io.circe.Error, A] =
    decode[A](messagingTopic.jsonCodec(value).noSpaces)(messagingTopic.jsonCodec)

  "ScheduledVideoDownloadTopic" should "round-trip a ScheduledVideoDownload through its JSON codec" in {
    val scheduledVideoDownload =
      ScheduledVideoDownload(
        timestamp,
        timestamp,
        SchedulingStatus.Error,
        1024,
        VideoMetadata(
          uri"https://spankbang.com/abc/video/title",
          "video-id",
          CustomVideoSite.SpankBang,
          "Sample title",
          FiniteDuration(212000, TimeUnit.MILLISECONDS),
          4096,
          FileResource("thumbnail-id", timestamp, "/opt/images/thumbnail.jpg", MediaType.image.jpeg, 256)
        ),
        Some(timestamp),
        Some(ErrorInfo("Download failed", "details"))
      )

    roundTrip(scheduledVideoDownload) mustBe Right(scheduledVideoDownload)
  }

  "DownloadProgressTopic" should "round-trip a DownloadProgress through its JSON codec" in {
    val downloadProgress = DownloadProgress("video-id", timestamp, 2048)

    roundTrip(downloadProgress) mustBe Right(downloadProgress)
  }

  "HttpMetricTopic" should "round-trip an HttpMetric through its JSON codec" in {
    val httpMetric =
      HttpMetric(
        Method.GET,
        uri"https://localhost/videos?page=1",
        FiniteDuration(150, TimeUnit.MILLISECONDS),
        Status.Ok,
        Some(MediaType.application.json),
        Some(512)
      )

    roundTrip(httpMetric) mustBe Right(httpMetric)
  }

  "WorkerStatusUpdateTopic" should "round-trip a WorkerStatusUpdate through its JSON codec" in {
    val workerStatusUpdate = WorkerStatusUpdate(WorkerStatus.Paused)

    roundTrip(workerStatusUpdate) mustBe Right(workerStatusUpdate)
  }

  "ScanVideoCommandTopic" should "round-trip a ScanVideosCommand through its JSON codec" in {
    val scanVideosCommand = ScanVideosCommand(timestamp)

    roundTrip(scanVideosCommand) mustBe Right(scanVideosCommand)
  }

  "VideoWatchMetricTopic" should "round-trip a VideoWatchMetric through its JSON codec" in {
    val videoWatchMetric = VideoWatchMetric("user-id", "video-file-id", 0, 1024, timestamp)

    roundTrip(videoWatchMetric) mustBe Right(videoWatchMetric)
  }
}
