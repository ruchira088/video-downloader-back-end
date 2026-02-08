package com.ruchij.api.services.asset

import com.ruchij.api.daos.user.models.User
import com.ruchij.api.services.asset.AssetService.FileByteRange
import com.ruchij.api.services.asset.models.Asset
import com.ruchij.api.services.asset.models.AssetType.{AlbumArt, Snapshot, Thumbnail, Video}

trait AssetService[F[_]] {
  def videoFile(
    id: String,
    user: User,
    fileByteRange: Option[FileByteRange],
    maybeMaxStreamSize: Option[Long]
  ): F[Asset[F, Video.type]]

  def snapshot(id: String, user: User): F[Asset[F, Snapshot.type]]

  def albumCover(id: String, user: User): F[Asset[F, AlbumArt.type]]

  def thumbnail(id: String): F[Asset[F, Thumbnail.type]]
}

object AssetService {
  final case class FileByteRange(start: Long, end: Option[Long])
}
