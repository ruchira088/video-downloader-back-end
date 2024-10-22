package com.ruchij.core.services.video

import com.ruchij.core.services.video.models.WatchedVideo
import org.joda.time.DateTime

import scala.concurrent.duration.FiniteDuration

trait VideoWatchHistoryService[F[_]] {
  def getWatchHistoryByUser(userId: String, pageSize: Int, pageNumber: Int): F[List[WatchedVideo]]

  def addWatchHistory(userId: String, videoId: String, timestamp: DateTime, duration: FiniteDuration): F[Unit]
}
