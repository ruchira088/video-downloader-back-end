package com.ruchij.api.daos.permission

import com.ruchij.core.daos.doobie.DoobieCustomMappings.dateTimePut
import com.ruchij.api.daos.permission.models.VideoPermission
import doobie.free.connection.ConnectionIO
import doobie.implicits.toSqlInterpolator

object DoobieVideoPermissionDao extends VideoPermissionDao[ConnectionIO] {

  override def insert(videoPermission: VideoPermission): ConnectionIO[Int] =
    sql"""
      INSERT INTO video_permission(granted_at, video_id, user_id)
        VALUES(${videoPermission.grantedAt}, ${videoPermission.scheduledVideoDownloadId}, ${videoPermission.userId})
    """
      .update
      .run

}
