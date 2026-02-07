package com.ruchij.core.messaging.models

import java.time.Instant

final case class VideoWatchMetric(
  userId: String,
  videoFileResourceId: String,
  startByte: Long,
  endByte: Long,
  timestamp: Instant
) {
  val size: Long = endByte - startByte
}
