package com.ruchij.core.daos.video.models

import com.ruchij.core.daos.videometadata.models.VideoSite

import scala.concurrent.duration.FiniteDuration

/** Totals for the videos of one site. The watch time is summed across every user. */
final case class VideoSiteSummary(
  site: VideoSite,
  videoCount: Int,
  totalSize: Long,
  totalDuration: FiniteDuration,
  watchTime: FiniteDuration
)
