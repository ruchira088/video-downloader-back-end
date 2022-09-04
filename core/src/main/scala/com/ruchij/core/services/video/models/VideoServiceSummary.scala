package com.ruchij.core.services.video.models

import com.ruchij.core.daos.videometadata.models.VideoSite

import scala.concurrent.duration.FiniteDuration

final case class VideoServiceSummary(videoCount: Int, totalSize: Long, totalDuration: FiniteDuration, sites: Set[VideoSite])
