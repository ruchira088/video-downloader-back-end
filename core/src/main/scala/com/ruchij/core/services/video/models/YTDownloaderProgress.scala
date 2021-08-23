package com.ruchij.core.services.video.models

import com.ruchij.core.utils.MatcherUtils.{DoubleNumber, FiniteDurationValue}

import scala.concurrent.duration.FiniteDuration
import scala.util.matching.Regex

case class YTDownloaderProgress(
  completed: Double,
  totalSize: YTDataSize,
  downloadRatePerSecond: YTDataSize,
  estimatedDuration: FiniteDuration
)

object YTDownloaderProgress {
  private val YTDownloaderProgressPattern: Regex =
    "\\[download\\]\\s* (\\S+)% of ~?(\\S+) at\\s* (\\S+)/s ETA (\\S+)".r

  def unapply(input: String): Option[YTDownloaderProgress] =
    input match {
      case YTDownloaderProgressPattern(
          DoubleNumber(completed),
          YTDataSize(totalSize),
          YTDataSize(downloadRate),
          FiniteDurationValue(estimatedDuration)
          ) =>
        Some(YTDownloaderProgress(completed, totalSize, downloadRate, estimatedDuration))

      case _ => None
    }
}
