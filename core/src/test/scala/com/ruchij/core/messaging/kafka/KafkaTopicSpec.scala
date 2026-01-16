package com.ruchij.core.messaging.kafka

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class KafkaTopicSpec extends AnyFlatSpec with Matchers {

  "ScheduledVideoDownloadTopic" should "have correct name" in {
    KafkaTopic.ScheduledVideoDownloadTopic.name mustBe "scheduled-video-downloads"
  }

  it should "have a valid codec" in {
    KafkaTopic.ScheduledVideoDownloadTopic.codec must not be null
  }

  "DownloadProgressTopic" should "have correct name" in {
    KafkaTopic.DownloadProgressTopic.name mustBe "download-progress-topic"
  }

  it should "have a valid codec" in {
    KafkaTopic.DownloadProgressTopic.codec must not be null
  }

  "HttpMetricTopic" should "have correct name" in {
    KafkaTopic.HttpMetricTopic.name mustBe "http-metrics"
  }

  it should "have a valid codec" in {
    KafkaTopic.HttpMetricTopic.codec must not be null
  }

  "WorkerStatusUpdateTopic" should "have correct name" in {
    KafkaTopic.WorkerStatusUpdateTopic.name mustBe "worker-status-updates"
  }

  it should "have a valid codec" in {
    KafkaTopic.WorkerStatusUpdateTopic.codec must not be null
  }

  "ScanVideoCommandTopic" should "have correct name" in {
    KafkaTopic.ScanVideoCommandTopic.name mustBe "scan-videos-command"
  }

  it should "have a valid codec" in {
    KafkaTopic.ScanVideoCommandTopic.codec must not be null
  }

  "VideoWatchMetricTopic" should "have correct name" in {
    KafkaTopic.VideoWatchMetricTopic.name mustBe "video-watch-metric"
  }

  it should "have a valid codec" in {
    KafkaTopic.VideoWatchMetricTopic.codec must not be null
  }
}
