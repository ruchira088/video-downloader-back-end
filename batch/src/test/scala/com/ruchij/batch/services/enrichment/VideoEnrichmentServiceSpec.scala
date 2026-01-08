package com.ruchij.batch.services.enrichment

import com.ruchij.core.test.data.CoreTestData
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.duration._

class VideoEnrichmentServiceSpec extends AnyFlatSpec with Matchers {

  "snapshotTimestamps" should "generate evenly spaced timestamps for snapshots" in {
    // Video with 13 minute duration (13 * 60 = 780 seconds)
    val video = CoreTestData.YouTubeVideo.copy(
      videoMetadata = CoreTestData.YouTubeVideo.videoMetadata.copy(duration = 780.seconds)
    )

    val timestamps = VideoEnrichmentService.snapshotTimestamps(video, 12)

    timestamps.size mustBe 12
    // Period = 780 / 13 = 60 seconds between each snapshot
    timestamps.head mustBe 60.seconds
    timestamps.last mustBe 720.seconds
  }

  it should "handle default snapshot count of 12" in {
    val video = CoreTestData.YouTubeVideo

    val timestamps = VideoEnrichmentService.snapshotTimestamps(video, VideoEnrichmentService.SnapshotCount)

    timestamps.size mustBe VideoEnrichmentService.SnapshotCount
  }

  it should "generate correct timestamps for short videos" in {
    val shortVideo = CoreTestData.YouTubeVideo.copy(
      videoMetadata = CoreTestData.YouTubeVideo.videoMetadata.copy(duration = 26.seconds)
    )

    val timestamps = VideoEnrichmentService.snapshotTimestamps(shortVideo, 12)

    timestamps.size mustBe 12
    // Period = 26 / 13 = 2 seconds
    timestamps.head mustBe 2.seconds
    timestamps(1) mustBe 4.seconds
    timestamps.last mustBe 24.seconds
  }

  it should "generate correct timestamps for very long videos" in {
    val longVideo = CoreTestData.YouTubeVideo.copy(
      videoMetadata = CoreTestData.YouTubeVideo.videoMetadata.copy(duration = 2.hours)
    )

    val timestamps = VideoEnrichmentService.snapshotTimestamps(longVideo, 12)

    timestamps.size mustBe 12
    // Period = 7200 / 13 ≈ 553 seconds
    timestamps.foreach { ts =>
      ts must be >= 0.seconds
      ts must be < 2.hours
    }
  }

  it should "handle single snapshot request" in {
    val video = CoreTestData.YouTubeVideo.copy(
      videoMetadata = CoreTestData.YouTubeVideo.videoMetadata.copy(duration = 100.seconds)
    )

    val timestamps = VideoEnrichmentService.snapshotTimestamps(video, 1)

    timestamps.size mustBe 1
    // Period = 100 / 2 = 50 seconds
    timestamps.head mustBe 50.seconds
  }

  "SnapshotCount" should "be 12" in {
    VideoEnrichmentService.SnapshotCount mustBe 12
  }
}
