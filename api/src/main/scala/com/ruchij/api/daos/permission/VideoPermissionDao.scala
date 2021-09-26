package com.ruchij.api.daos.permission

import com.ruchij.api.daos.permission.models.VideoPermission

trait VideoPermissionDao[F[_]] {
  def insert(videoPermission: VideoPermission): F[Int]
}
