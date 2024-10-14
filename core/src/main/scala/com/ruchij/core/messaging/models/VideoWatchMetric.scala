package com.ruchij.core.messaging.models

import org.joda.time.DateTime

final case class VideoWatchMetric(
  userId: String,
  videoFileResourceId: String,
  startByte: Long,
  endByte: Long,
  timestamp: DateTime
) {
  val size: Long = endByte - startByte
}
