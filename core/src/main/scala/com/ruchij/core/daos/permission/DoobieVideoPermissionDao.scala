package com.ruchij.core.daos.permission

import cats.ApplicativeError
import com.ruchij.core.daos.doobie.DoobieCustomMappings.{dateTimeGet, dateTimePut}
import com.ruchij.core.daos.permission.models.VideoPermission
import doobie.free.connection.ConnectionIO
import doobie.generic.auto._
import doobie.implicits.toSqlInterpolator
import doobie.util.fragments.whereAndOpt

object DoobieVideoPermissionDao extends VideoPermissionDao[ConnectionIO] {

  override def insert(videoPermission: VideoPermission): ConnectionIO[Int] =
    sql"""
      INSERT INTO permission(created_at, video_id, user_id)
        VALUES(${videoPermission.grantedAt}, ${videoPermission.scheduledVideoDownloadId}, ${videoPermission.userId})
    """.update.run

  override def find(
    maybeUserId: Option[String],
    maybeScheduledVideoId: Option[String]
  ): ConnectionIO[Seq[VideoPermission]] =
    (fr"SELECT created_at, video_id, user_id FROM permission" ++
      whereAndOpt(
        maybeUserId.map(userId => fr"user_id = $userId"),
        maybeScheduledVideoId.map(videoId => fr"video_id = $videoId")
      ))
      .query[VideoPermission]
      .to[Seq]

  override def delete(maybeUserId: Option[String], maybeScheduledVideoId: Option[String]): ConnectionIO[Int] =
    if (maybeUserId.isEmpty && maybeScheduledVideoId.isEmpty)
      ApplicativeError[ConnectionIO, Throwable].raiseError {
        new IllegalArgumentException("Both userId and scheduledVideoId cannot be empty")
      } else
      (fr"DELETE FROM permission" ++ whereAndOpt(
        maybeUserId.map(userId => fr"user_id = $userId"),
        maybeScheduledVideoId.map(videoId => fr"video_id = $videoId")
      )).update.run
}
