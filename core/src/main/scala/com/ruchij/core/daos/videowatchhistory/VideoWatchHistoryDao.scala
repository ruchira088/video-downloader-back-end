package com.ruchij.core.daos.videowatchhistory

import com.ruchij.core.daos.videowatchhistory.models.{DetailedVideoWatchHistory, VideoWatchHistory}
import java.time.Instant

trait VideoWatchHistoryDao[F[_]] {
  def insert(videoWatchHistory: VideoWatchHistory): F[Unit]

  def findBy(userId: String, pageSize: Int, pageNumber: Int): F[List[DetailedVideoWatchHistory]]

  def findBy(userId: String, videoId: String, pageSize: Int, pageNumber: Int): F[List[DetailedVideoWatchHistory]]

  def findLastUpdatedAfter(userId: String, videoId: String, timestamp: Instant): F[Option[VideoWatchHistory]]

  def update(updatedVideoWatchHistory: VideoWatchHistory): F[Unit]

  def deleteBy(videoId: String): F[Int]
}
