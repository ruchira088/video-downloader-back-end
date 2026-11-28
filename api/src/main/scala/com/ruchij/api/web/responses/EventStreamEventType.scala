package com.ruchij.api.web.responses

import com.ruchij.core.types.{EnumEntry, EnumValues}

enum EventStreamEventType(override val entryName: String) extends EnumEntry {
  case HeartBeat extends EventStreamEventType("heart-beat")
  case ActiveDownload extends EventStreamEventType("active-download")
  case ScheduledVideoDownloadUpdate extends EventStreamEventType("scheduled-video-download-update")

  // The event type field of a server-sent event
  def eventType: Some[String] = Some(entryName)
}

object EventStreamEventType extends EnumValues[EventStreamEventType]
