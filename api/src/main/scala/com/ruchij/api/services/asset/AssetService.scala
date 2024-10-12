package com.ruchij.api.services.asset

import com.ruchij.api.services.asset.models.Asset
import AssetService.FileByteRange
import com.ruchij.api.daos.user.models.User

trait AssetService[F[_]] {
  def videoFile(id: String, user: User, fileByteRange: Option[FileByteRange]): F[Asset[F]]

  def snapshot(id: String, user: User): F[Asset[F]]

  def thumbnail(id: String): F[Asset[F]]
}

object AssetService {
  final case class FileByteRange(start: Long, end: Option[Long])
}
