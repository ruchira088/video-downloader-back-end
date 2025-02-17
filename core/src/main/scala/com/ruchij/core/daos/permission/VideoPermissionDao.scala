package com.ruchij.core.daos.permission

import com.ruchij.core.daos.permission.models.VideoPermission

trait VideoPermissionDao[F[_]] {
  def insert(videoPermission: VideoPermission): F[Int]

  def find(userId: Option[String], scheduledVideoId: Option[String]): F[Seq[VideoPermission]]

  def delete(maybeUserId: Option[String], maybeScheduledVideoId: Option[String]): F[Int]
}
