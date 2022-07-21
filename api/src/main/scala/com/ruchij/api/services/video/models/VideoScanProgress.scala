package com.ruchij.api.services.video.models

import org.joda.time.DateTime

sealed trait VideoScanProgress {
  val startedAt: DateTime
}

object VideoScanProgress {
  case class ScanInProgress(startedAt: DateTime) extends VideoScanProgress
  case class ScanStarted(startedAt: DateTime) extends VideoScanProgress
}
