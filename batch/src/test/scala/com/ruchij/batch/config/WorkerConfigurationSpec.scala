package com.ruchij.batch.config

import org.joda.time.LocalTime
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class WorkerConfigurationSpec extends AnyFlatSpec with Matchers {

  "WorkerConfiguration" should "store all configuration values" in {
    val startTime = new LocalTime(9, 0)
    val endTime = new LocalTime(18, 0)

    val config = WorkerConfiguration(
      maxConcurrentDownloads = 5,
      startTime = startTime,
      endTime = endTime,
      owner = "test-owner"
    )

    config.maxConcurrentDownloads mustBe 5
    config.startTime mustBe startTime
    config.endTime mustBe endTime
    config.owner mustBe "test-owner"
  }

  it should "support 24/7 operation with same start and end time" in {
    val sameTime = new LocalTime(0, 0)

    val config = WorkerConfiguration(
      maxConcurrentDownloads = 10,
      startTime = sameTime,
      endTime = sameTime,
      owner = "always-on"
    )

    config.startTime mustBe config.endTime
  }

  it should "support overnight work periods" in {
    val startTime = new LocalTime(22, 0)
    val endTime = new LocalTime(6, 0)

    val config = WorkerConfiguration(
      maxConcurrentDownloads = 3,
      startTime = startTime,
      endTime = endTime,
      owner = "night-worker"
    )

    config.startTime.isAfter(config.endTime) mustBe true
  }

  it should "support equality" in {
    val time1 = new LocalTime(9, 0)
    val time2 = new LocalTime(17, 0)

    val config1 = WorkerConfiguration(4, time1, time2, "owner1")
    val config2 = WorkerConfiguration(4, time1, time2, "owner1")

    config1 mustBe config2
  }

  it should "support copy" in {
    val config = WorkerConfiguration(
      maxConcurrentDownloads = 5,
      startTime = new LocalTime(9, 0),
      endTime = new LocalTime(17, 0),
      owner = "original"
    )

    val modified = config.copy(maxConcurrentDownloads = 10)

    modified.maxConcurrentDownloads mustBe 10
    modified.owner mustBe "original"
  }

  it should "handle single concurrent download" in {
    val config = WorkerConfiguration(
      maxConcurrentDownloads = 1,
      startTime = new LocalTime(0, 0),
      endTime = new LocalTime(0, 0),
      owner = "single"
    )

    config.maxConcurrentDownloads mustBe 1
  }

  it should "handle many concurrent downloads" in {
    val config = WorkerConfiguration(
      maxConcurrentDownloads = 100,
      startTime = new LocalTime(0, 0),
      endTime = new LocalTime(0, 0),
      owner = "bulk"
    )

    config.maxConcurrentDownloads mustBe 100
  }
}
