package com.ruchij.api.daos.title

import cats.ApplicativeError
import com.ruchij.api.daos.title.models.VideoTitle
import doobie.free.connection.ConnectionIO
import doobie.generic.auto._
import doobie.implicits.toSqlInterpolator
import doobie.util.fragments.whereAndOpt

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

  override def delete(maybeVideoId: Option[String], maybeUserId: Option[String]): ConnectionIO[Int] =
    if (maybeVideoId.isEmpty && maybeUserId.isEmpty)
      ApplicativeError[ConnectionIO, Throwable].raiseError {
        new IllegalArgumentException("Both videoId and userId cannot be empty")
      }
    else
      (fr"DELETE FROM video_title" ++ whereAndOpt(maybeVideoId.map(videoId => fr"video_id = $videoId"), maybeUserId.map(userId => fr"user_id = $userId")))
        .update
        .run
}
