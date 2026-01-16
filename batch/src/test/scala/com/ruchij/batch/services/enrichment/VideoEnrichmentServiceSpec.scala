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

  "snapshotTimestamps" should "return empty for 0 snapshots" in {
    val video = CoreTestData.YouTubeVideo

    val timestamps = VideoEnrichmentService.snapshotTimestamps(video, 0)

    timestamps must be(empty)
  }

  it should "never include timestamp 0" in {
    val video = CoreTestData.YouTubeVideo.copy(
      videoMetadata = CoreTestData.YouTubeVideo.videoMetadata.copy(duration = 120.seconds)
    )

    val timestamps = VideoEnrichmentService.snapshotTimestamps(video, 5)

    timestamps must not contain 0.seconds
    all(timestamps.map(_.toNanos)) must be > 0L
  }

  it should "never include timestamp at or beyond video duration" in {
    val video = CoreTestData.YouTubeVideo.copy(
      videoMetadata = CoreTestData.YouTubeVideo.videoMetadata.copy(duration = 100.seconds)
    )

    val timestamps = VideoEnrichmentService.snapshotTimestamps(video, 10)

    all(timestamps) must be < video.videoMetadata.duration
  }

  it should "return two evenly spaced timestamps for 2 snapshots" in {
    val video = CoreTestData.YouTubeVideo.copy(
      videoMetadata = CoreTestData.YouTubeVideo.videoMetadata.copy(duration = 90.seconds)
    )

    val timestamps = VideoEnrichmentService.snapshotTimestamps(video, 2)

    timestamps.size mustBe 2
    // period = 90 / 3 = 30 seconds
    timestamps.head mustBe 30.seconds
    timestamps.last mustBe 60.seconds
  }

  it should "handle millisecond precision" in {
    val video = CoreTestData.YouTubeVideo.copy(
      videoMetadata = CoreTestData.YouTubeVideo.videoMetadata.copy(duration = 1300.millis)
    )

    val timestamps = VideoEnrichmentService.snapshotTimestamps(video, 12)

    timestamps.size mustBe 12
    // period = 1300 / 13 = 100ms
    timestamps.head mustBe 100.millis
    timestamps.last mustBe 1200.millis
  }

  it should "work correctly with different snapshot counts" in {
    val video = CoreTestData.YouTubeVideo.copy(
      videoMetadata = CoreTestData.YouTubeVideo.videoMetadata.copy(duration = 100.seconds)
    )

    for (count <- 1 to 20) {
      val timestamps = VideoEnrichmentService.snapshotTimestamps(video, count)
      timestamps.size mustBe count
      all(timestamps) must be > 0.seconds
      all(timestamps) must be < video.videoMetadata.duration
    }
  }
}
