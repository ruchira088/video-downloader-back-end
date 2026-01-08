package com.ruchij.core.messaging.models

import org.joda.time.DateTime
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class VideoWatchMetricSpec extends AnyFlatSpec with Matchers {

  private val testTimestamp = new DateTime(2022, 8, 1, 10, 10, 0, 0)

  "VideoWatchMetric.size" should "calculate the correct size from byte range" in {
    val metric = VideoWatchMetric(
      userId = "user-123",
      videoFileResourceId = "video-456",
      startByte = 0,
      endByte = 1000,
      timestamp = testTimestamp
    )

    metric.size mustBe 1000
  }

  it should "handle zero-length ranges" in {
    val metric = VideoWatchMetric(
      userId = "user-123",
      videoFileResourceId = "video-456",
      startByte = 500,
      endByte = 500,
      timestamp = testTimestamp
    )

    metric.size mustBe 0
  }

  it should "handle large byte ranges" in {
    val metric = VideoWatchMetric(
      userId = "user-123",
      videoFileResourceId = "video-456",
      startByte = 0,
      endByte = 5000000000L,
      timestamp = testTimestamp
    )

    metric.size mustBe 5000000000L
  }

  it should "handle partial ranges" in {
    val metric = VideoWatchMetric(
      userId = "user-123",
      videoFileResourceId = "video-456",
      startByte = 1000,
      endByte = 2000,
      timestamp = testTimestamp
    )

    metric.size mustBe 1000
  }

  "VideoWatchMetric" should "store all provided values" in {
    val metric = VideoWatchMetric(
      userId = "test-user",
      videoFileResourceId = "test-video",
      startByte = 100,
      endByte = 500,
      timestamp = testTimestamp
    )

    metric.userId mustBe "test-user"
    metric.videoFileResourceId mustBe "test-video"
    metric.startByte mustBe 100
    metric.endByte mustBe 500
    metric.timestamp mustBe testTimestamp
  }
}
