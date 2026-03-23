package com.ruchij.core.messaging.kafka

import com.ruchij.core.messaging.MessagingTopic
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class MessagingTopicSpec extends AnyFlatSpec with Matchers {

  "ScheduledVideoDownloadTopic" should "have correct name" in {
    MessagingTopic.ScheduledVideoDownloadTopic.name mustBe "scheduled-video-downloads"
  }

  it should "have a valid codec" in {
    MessagingTopic.ScheduledVideoDownloadTopic.avroCodec must not be null
  }

  "DownloadProgressTopic" should "have correct name" in {
    MessagingTopic.DownloadProgressTopic.name mustBe "download-progress-topic"
  }

  it should "have a valid codec" in {
    MessagingTopic.DownloadProgressTopic.avroCodec must not be null
  }

  "HttpMetricTopic" should "have correct name" in {
    MessagingTopic.HttpMetricTopic.name mustBe "http-metrics"
  }

  it should "have a valid codec" in {
    MessagingTopic.HttpMetricTopic.avroCodec must not be null
  }

  "WorkerStatusUpdateTopic" should "have correct name" in {
    MessagingTopic.WorkerStatusUpdateTopic.name mustBe "worker-status-updates"
  }

  it should "have a valid codec" in {
    MessagingTopic.WorkerStatusUpdateTopic.avroCodec must not be null
  }

  "ScanVideoCommandTopic" should "have correct name" in {
    MessagingTopic.ScanVideoCommandTopic.name mustBe "scan-videos-command"
  }

  it should "have a valid codec" in {
    MessagingTopic.ScanVideoCommandTopic.avroCodec must not be null
  }

  "VideoWatchMetricTopic" should "have correct name" in {
    MessagingTopic.VideoWatchMetricTopic.name mustBe "video-watch-metric"
  }

  it should "have a valid codec" in {
    MessagingTopic.VideoWatchMetricTopic.avroCodec must not be null
  }
}
