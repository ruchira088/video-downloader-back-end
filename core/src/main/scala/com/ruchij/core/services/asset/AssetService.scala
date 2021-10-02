package com.ruchij.core.services.asset

import com.ruchij.core.services.asset.AssetService.FileByteRange
import com.ruchij.core.services.asset.models.Asset

trait AssetService[F[_]] {
  def videoFile(id: String, maybeUserId: Option[String], fileByteRange: Option[FileByteRange]): F[Asset[F]]

  def snapshot(id: String, maybeUserId: Option[String]): F[Asset[F]]

  def thumbnail(id: String): F[Asset[F]]
}

object AssetService {
  case class FileByteRange(start: Long, end: Option[Long])
}
