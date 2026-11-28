package com.ruchij.api.web.responses

import enumeratum.{Enum, EnumEntry}

import scala.language.implicitConversions

sealed trait EventStreamEventType extends EnumEntry {
  // The event type field of a server-sent event
  def eventType: Some[String] = Some(entryName)
}

object EventStreamEventType extends Enum[EventStreamEventType] {

  case object HeartBeat extends EventStreamEventType {
    override def entryName: String = "heart-beat"
  }

  case object ActiveDownload extends EventStreamEventType {
    override def entryName: String = "active-download"
  }

  case object ScheduledVideoDownloadUpdate extends EventStreamEventType {
    override def entryName: String = "scheduled-video-download-update"
  }

  override def values: IndexedSeq[EventStreamEventType] = findValues
}
