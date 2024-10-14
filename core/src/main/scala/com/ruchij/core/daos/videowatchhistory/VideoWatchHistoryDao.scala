package com.ruchij.core.daos.videowatchhistory

import com.ruchij.core.daos.videowatchhistory.models.VideoWatchHistory
import org.joda.time.DateTime

trait VideoWatchHistoryDao[F[_]] {
  def insert(videoWatchHistory: VideoWatchHistory): F[Unit]

  def findBy(userId: String, pageSize: Int, pageNumber: Int): F[List[VideoWatchHistory]]

  def findBy(userId: String, videoId: String): F[List[VideoWatchHistory]]

  def findLastUpdatedAfter(userId: String, videoId: String, timestamp: DateTime): F[Option[VideoWatchHistory]]

  def update(updatedVideoWatchHistory: VideoWatchHistory): F[Unit]
}
