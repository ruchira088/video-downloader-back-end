package com.ruchij.core.formats

import com.ruchij.core.commands.ScanVideosCommand
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.scheduling.models.ScheduledVideoDownload.ErrorInfo
import com.ruchij.core.daos.scheduling.models.{ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.daos.videometadata.models.{CustomVideoSite, VideoMetadata}
import com.ruchij.core.daos.workers.models.VideoScan.ScanStatus
import com.ruchij.core.daos.workers.models.{VideoScan, WorkerStatus}
import com.ruchij.core.messaging.models.{HttpMetric, VideoWatchMetric}
import com.ruchij.core.services.scheduling.models.{DownloadProgress, WorkerStatusUpdate}
import com.ruchij.core.types.TimeUtils
import org.http4s.implicits.*
import org.http4s.{MediaType, Method, Status}

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

// Add every new MessagingTopic defined in core here, so that its JSON format is pinned too, and likewise every type
// stored in the key-value store
class CoreGoldenFormatSpec extends GoldenFormatSpec("core", CoreGoldenFormatSpec.formats)

object CoreGoldenFormatSpec {
  private val timestamp = TimeUtils.instantOf(2024, 3, 10, 8, 15)

  val scheduledVideoDownload: ScheduledVideoDownload =
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
      Some(ErrorInfo("Download failed", "details")),
      retryCount = 2,
      retryAfter = Some(timestamp)
    )

  val formats: List[GoldenFormat] =
    List(
      GoldenFormat.json(scheduledVideoDownload),
      GoldenFormat.json(DownloadProgress("video-id", timestamp, 2048)),
      GoldenFormat.json(
        HttpMetric(
          Method.GET,
          uri"https://localhost/videos?page=1",
          FiniteDuration(150, TimeUnit.MILLISECONDS),
          Status.Ok,
          Some(MediaType.application.json),
          Some(512)
        )
      ),
      GoldenFormat.json(WorkerStatusUpdate(WorkerStatus.Paused)),
      GoldenFormat.json(ScanVideosCommand(timestamp)),
      GoldenFormat.json(VideoWatchMetric("user-id", "video-file-id", 0, 1024, timestamp)),
      GoldenFormat.kvValue("video-scan", VideoScan(timestamp, ScanStatus.InProgress))
    )
}
