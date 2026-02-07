package com.ruchij.core.daos.videowatchhistory

import cats.data.NonEmptySeq
import com.ruchij.core.daos.doobie.DoobieCustomMappings._
import com.ruchij.core.daos.doobie.DoobieUtils.SingleUpdateOps
import com.ruchij.core.daos.videowatchhistory.models.{DetailedVideoWatchHistory, VideoWatchHistory}
import doobie.ConnectionIO
import doobie.implicits._
import doobie.util.fragment.Fragment
import doobie.util.fragments.whereAnd
import java.time.Instant

object DoobieVideoWatchHistoryDao extends VideoWatchHistoryDao[ConnectionIO] {
  private val SelectQuery =
    fr"""
       SELECT
        video_watch_history.id,
        video_watch_history.user_id,
        video_metadata.url,
        video_metadata.id,
        video_metadata.video_site,
        video_metadata.title,
        video_metadata.duration,
        video_metadata.size,
        thumbnail.id, thumbnail.created_at, thumbnail.path, thumbnail.media_type, thumbnail.size,
        video_file.id,
        video_file.created_at,
        video_file.path,
        video_file.media_type,
        video_file.size,
        video.created_at,
        video_watch_time.watch_time_in_ms,
        video_watch_history.created_at,
        video_watch_history.last_updated_at,
        video_watch_history.duration_in_ms
      FROM video_watch_history
        INNER JOIN video ON video_watch_history.video_id = video.video_metadata_id
        INNER JOIN video_metadata ON video.video_metadata_id = video_metadata.id
        INNER JOIN file_resource AS thumbnail ON video_metadata.thumbnail_id = thumbnail.id
        INNER JOIN file_resource AS video_file ON video.file_resource_id = video_file.id
        INNER JOIN video_watch_time ON video_watch_history.video_id = video_watch_time.video_id
    """

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

  private def find(
    pageSize: Int,
    pageNumber: Int,
    whereFragments: NonEmptySeq[Fragment]
  ): ConnectionIO[List[DetailedVideoWatchHistory]] =
    (SelectQuery ++
      whereAnd(whereFragments.head, whereFragments.tail: _*) ++
      fr"""
          ORDER BY video_watch_history.last_updated_at DESC
            LIMIT $pageSize OFFSET ${pageNumber * pageSize}
      """)
      .query[DetailedVideoWatchHistory]
      .to[List]

  override def findBy(userId: String, pageSize: Int, pageNumber: Int): ConnectionIO[List[DetailedVideoWatchHistory]] =
    find(pageSize, pageNumber, NonEmptySeq.one(fr"video_watch_history.user_id = $userId"))

  override def findBy(
    userId: String,
    videoId: String,
    pageSize: Int,
    pageNumber: Int
  ): ConnectionIO[List[DetailedVideoWatchHistory]] =
    find(
      pageSize,
      pageNumber,
      NonEmptySeq(fr"video_watch_history.user_id = $userId", List(fr"video_watch_history.video_id = $videoId"))
    )

  override def findLastUpdatedAfter(
    userId: String,
    videoId: String,
    timestamp: Instant
  ): ConnectionIO[Option[VideoWatchHistory]] =
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
    """.update.run.one

  override def deleteBy(videoId: String): ConnectionIO[Int] =
    sql"DELETE FROM video_watch_history WHERE video_id = $videoId".update.run
}
