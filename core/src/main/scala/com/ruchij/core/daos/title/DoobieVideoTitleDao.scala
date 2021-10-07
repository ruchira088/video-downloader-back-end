package com.ruchij.core.daos.title

import com.ruchij.core.daos.title.models.VideoTitle
import doobie.free.connection.ConnectionIO
import doobie.implicits.toSqlInterpolator

object DoobieVideoTitleDao extends VideoTitleDao[ConnectionIO] {
  override def insert(videoTitle: VideoTitle): ConnectionIO[Int] =
    sql"""
        INSERT INTO video_title(video_id, user_id, title)
            VALUES(${videoTitle.videoId}, ${videoTitle.userId}, ${videoTitle.title})
    """
      .update
      .run

  override def find(videoId: String, userId: String): ConnectionIO[Option[VideoTitle]] =
    sql"SELECT video_id, user_id, title FROM video_title WHERE video_id = $videoId AND user_id = $userId"
      .query[VideoTitle]
      .option

  override def update(videoId: String, userId: String, title: String): ConnectionIO[Int] =
    sql"UPDATE video_title SET title = $title WHERE video_id = $videoId AND user_id = $userId"
      .update
      .run

  override def deleteByUserId(userId: String): ConnectionIO[Int] =
    sql"DELETE FROM video_title WHERE user_id = $userId".update.run
}
