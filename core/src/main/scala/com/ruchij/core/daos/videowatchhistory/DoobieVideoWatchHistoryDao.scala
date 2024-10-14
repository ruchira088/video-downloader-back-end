package com.ruchij.core.daos.videowatchhistory

import com.ruchij.core.daos.doobie.DoobieCustomMappings._
import com.ruchij.core.daos.doobie.DoobieUtils.SingleUpdateOps
import com.ruchij.core.daos.videowatchhistory.models.VideoWatchHistory
import doobie.ConnectionIO
import doobie.implicits._
import org.joda.time.DateTime

object DoobieVideoWatchHistoryDao extends VideoWatchHistoryDao[ConnectionIO] {

  override def insert(videoWatchHistory: VideoWatchHistory): ConnectionIO[Unit] =
    sql"""
         INSERT INTO video_watch_history (id, created_at, last_updated_at, user_id, video_id, duration_in_ms)
          VALUES (
            ${videoWatchHistory.id},
            ${videoWatchHistory.createdAt},
            ${videoWatchHistory.lastUpdatedAt},
            ${videoWatchHistory.userId},
            ${videoWatchHistory.videoId},
            ${videoWatchHistory.duration}
          )
       """.update.run.one

  override def findBy(userId: String, pageSize: Int, pageNumber: Int): ConnectionIO[List[VideoWatchHistory]] =
    sql"""
      SELECT id, user_id, video_id, created_at, last_updated_at, duration_in_ms
        FROM video_watch_history WHERE user_id = $userId LIMIT $pageSize OFFSET ${pageNumber * pageSize}
    """
      .query[VideoWatchHistory]
      .to[List]

  override def findBy(userId: String, videoId: String): ConnectionIO[List[VideoWatchHistory]] =
    sql"""
      SELECT id, user_id, video_id, created_at, last_updated_at, duration_in_ms
        FROM video_watch_history WHERE user_id = $userId AND video_id = $videoId
    """
      .query[VideoWatchHistory]
      .to[List]

  override def findLastUpdatedAfter(userId: String, videoId: String, timestamp: DateTime): ConnectionIO[Option[VideoWatchHistory]] =
    sql"""
      SELECT id, user_id, video_id, created_at, last_updated_at, duration_in_ms
        FROM video_watch_history WHERE user_id = $userId AND video_id = $videoId AND last_updated_at > $timestamp
        LIMIT 1
    """
      .query[VideoWatchHistory]
      .option

  override def update(updatedVideoWatchHistory: VideoWatchHistory): ConnectionIO[Unit] =
    sql"""
      UPDATE video_watch_history
        SET
          created_at = ${updatedVideoWatchHistory.createdAt},
          last_updated_at = ${updatedVideoWatchHistory.lastUpdatedAt},
          user_id = ${updatedVideoWatchHistory.userId},
          video_id = ${updatedVideoWatchHistory.videoId},
          duration_in_ms = ${updatedVideoWatchHistory.duration}
        WHERE id = ${updatedVideoWatchHistory.id}
    """
      .update
      .run
      .one
}
