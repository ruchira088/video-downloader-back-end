package com.ruchij.core.messaging.kafka

import com.ruchij.core.messaging.MessagingTopic
import org.apache.avro.{Schema, SchemaCompatibility}
import org.apache.avro.SchemaCompatibility.SchemaCompatibilityType
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.jdk.CollectionConverters._

class MessagingTopicSpec extends AnyFlatSpec with Matchers {

  "ScheduledVideoDownloadTopic" should "have correct name" in {
    MessagingTopic.ScheduledVideoDownloadTopic.name mustBe "scheduled-video-downloads"
  }

  it should "have a valid codec" in {
    MessagingTopic.ScheduledVideoDownloadTopic.avroCodec must not be null
  }

  it should "read records written with the schema from before retries existed" in {
    val schema: Schema =
      MessagingTopic.ScheduledVideoDownloadTopic.avroCodec.schema.fold(error => fail(error.message), identity)

    schema.getField("retryCount") must not be null
    schema.getField("retryAfter") must not be null

    val legacyFields =
      schema.getFields.asScala.toList
        .filterNot(field => Set("retryCount", "retryAfter").contains(field.name()))
        .map(field => new Schema.Field(field, field.schema()))

    val legacySchema =
      Schema.createRecord(schema.getName, schema.getDoc, schema.getNamespace, false, legacyFields.asJava)

    // Schema Registry's default BACKWARD check: the new schema must be able to read data written with the old one
    SchemaCompatibility.checkReaderWriterCompatibility(schema, legacySchema).getType mustBe
      SchemaCompatibilityType.COMPATIBLE
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
