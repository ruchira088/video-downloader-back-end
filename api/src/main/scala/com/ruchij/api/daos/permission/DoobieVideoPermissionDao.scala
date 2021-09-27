package com.ruchij.api.daos.permission

import com.ruchij.core.daos.doobie.DoobieCustomMappings.{dateTimeGet, dateTimePut}
import com.ruchij.api.daos.permission.models.VideoPermission
import doobie.free.connection.ConnectionIO
import doobie.implicits.toSqlInterpolator
import doobie.util.fragments.whereAndOpt

object DoobieVideoPermissionDao extends VideoPermissionDao[ConnectionIO] {

  override def insert(videoPermission: VideoPermission): ConnectionIO[Int] =
    sql"""
      INSERT INTO video_permission(granted_at, video_id, user_id)
        VALUES(${videoPermission.grantedAt}, ${videoPermission.scheduledVideoDownloadId}, ${videoPermission.userId})
    """
      .update
      .run

  override def find(maybeUserId: Option[String], maybeScheduledVideoId: Option[String]): ConnectionIO[Seq[VideoPermission]] =
    (fr"SELECT granted_at, video_id, user_id FROM video_permission" ++
      whereAndOpt(
        maybeUserId.map(userId => fr"user_id = $userId"),
        maybeScheduledVideoId.map(videoId => fr"video_id = $videoId")
      ))
      .query[VideoPermission]
      .to[Seq]
}
