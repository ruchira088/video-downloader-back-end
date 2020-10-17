package com.ruchij.core.services.video.models

import scala.concurrent.duration.FiniteDuration

case class VideoServiceSummary(videoCount: Int, totalSize: Long, totalDuration: FiniteDuration)
