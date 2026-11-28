package com.ruchij.api.web.responses

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class EventStreamEventTypeSpec extends AnyFlatSpec with Matchers {

  "EventStreamEventType.values" should "contain all event types" in {
    val values = EventStreamEventType.values

    values must contain(EventStreamEventType.HeartBeat)
    values must contain(EventStreamEventType.ActiveDownload)
    values must contain(EventStreamEventType.ScheduledVideoDownloadUpdate)
    values must have length 3
  }

  "HeartBeat" should "have correct entry name" in {
    EventStreamEventType.HeartBeat.entryName mustBe "heart-beat"
  }

  "ActiveDownload" should "have correct entry name" in {
    EventStreamEventType.ActiveDownload.entryName mustBe "active-download"
  }

  "ScheduledVideoDownloadUpdate" should "have correct entry name" in {
    EventStreamEventType.ScheduledVideoDownloadUpdate.entryName mustBe "scheduled-video-download-update"
  }

  "eventType" should "convert HeartBeat to Some[String]" in {
    val result: Some[String] = EventStreamEventType.HeartBeat.eventType
    result mustBe Some("heart-beat")
  }

  it should "convert ActiveDownload to Some[String]" in {
    val result: Some[String] = EventStreamEventType.ActiveDownload.eventType
    result mustBe Some("active-download")
  }

  it should "convert ScheduledVideoDownloadUpdate to Some[String]" in {
    val result: Some[String] = EventStreamEventType.ScheduledVideoDownloadUpdate.eventType
    result mustBe Some("scheduled-video-download-update")
  }

  "withNameInsensitive" should "find HeartBeat by entry name" in {
    EventStreamEventType.withNameInsensitive("heart-beat") mustBe EventStreamEventType.HeartBeat
  }

  it should "find ActiveDownload by entry name" in {
    EventStreamEventType.withNameInsensitive("active-download") mustBe EventStreamEventType.ActiveDownload
  }

  it should "find ScheduledVideoDownloadUpdate by entry name" in {
    EventStreamEventType.withNameInsensitive(
      "scheduled-video-download-update"
    ) mustBe EventStreamEventType.ScheduledVideoDownloadUpdate
  }
}
