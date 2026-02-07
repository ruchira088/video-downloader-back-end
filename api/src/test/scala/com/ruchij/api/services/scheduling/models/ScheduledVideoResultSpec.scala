package com.ruchij.api.services.scheduling.models

import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.scheduling.models.{ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.daos.videometadata.models.{VideoMetadata, VideoSite}
import org.http4s.MediaType
import org.http4s.implicits.http4sLiteralsSyntax
import com.ruchij.core.types.TimeUtils
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.duration._

class ScheduledVideoResultSpec extends AnyFlatSpec with Matchers {

  private val timestamp = TimeUtils.instantOf(2024, 5, 15, 10, 30)

  private val sampleThumbnail =
    FileResource("thumbnail-1", timestamp, "/thumbnails/thumb1.jpg", MediaType.image.jpeg, 50000L)

  private val sampleVideoMetadata = VideoMetadata(
    uri"https://example.com/video1",
    "video-1",
    VideoSite.YTDownloaderSite("youtube"),
    "Sample Video",
    10.minutes,
    1024 * 1024 * 100L,
    sampleThumbnail
  )

  private val sampleScheduledVideoDownload = ScheduledVideoDownload(
    timestamp,
    timestamp,
    SchedulingStatus.Queued,
    0L,
    sampleVideoMetadata,
    None,
    None
  )

  "AlreadyScheduled" should "have isNew as false" in {
    val result = ScheduledVideoResult.AlreadyScheduled(sampleScheduledVideoDownload)

    result.isNew mustBe false
  }

  it should "return the provided ScheduledVideoDownload" in {
    val result = ScheduledVideoResult.AlreadyScheduled(sampleScheduledVideoDownload)

    result.scheduledVideoDownload mustBe sampleScheduledVideoDownload
  }

  "NewlyScheduled" should "have isNew as true" in {
    val result = ScheduledVideoResult.NewlyScheduled(sampleScheduledVideoDownload)

    result.isNew mustBe true
  }

  it should "return the provided ScheduledVideoDownload" in {
    val result = ScheduledVideoResult.NewlyScheduled(sampleScheduledVideoDownload)

    result.scheduledVideoDownload mustBe sampleScheduledVideoDownload
  }

  "ScheduledVideoResult" should "be a sealed trait with two implementations" in {
    val alreadyScheduled: ScheduledVideoResult = ScheduledVideoResult.AlreadyScheduled(sampleScheduledVideoDownload)
    val newlyScheduled: ScheduledVideoResult = ScheduledVideoResult.NewlyScheduled(sampleScheduledVideoDownload)

    alreadyScheduled mustBe a[ScheduledVideoResult]
    newlyScheduled mustBe a[ScheduledVideoResult]
  }

  it should "allow pattern matching" in {
    val result: ScheduledVideoResult = ScheduledVideoResult.AlreadyScheduled(sampleScheduledVideoDownload)

    val message = result match {
      case ScheduledVideoResult.AlreadyScheduled(svd) => s"Already scheduled: ${svd.videoMetadata.id}"
      case ScheduledVideoResult.NewlyScheduled(svd) => s"Newly scheduled: ${svd.videoMetadata.id}"
    }

    message mustBe "Already scheduled: video-1"
  }

  it should "correctly distinguish between AlreadyScheduled and NewlyScheduled" in {
    val alreadyScheduled = ScheduledVideoResult.AlreadyScheduled(sampleScheduledVideoDownload)
    val newlyScheduled = ScheduledVideoResult.NewlyScheduled(sampleScheduledVideoDownload)

    alreadyScheduled.isNew mustBe false
    newlyScheduled.isNew mustBe true
    alreadyScheduled.scheduledVideoDownload mustBe newlyScheduled.scheduledVideoDownload
  }
}
