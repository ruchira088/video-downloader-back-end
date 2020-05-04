package com.ruchij.services.asset

import com.ruchij.services.asset.models.Asset

trait AssetService[F[_]] {
  def retrieve(id: String, start: Option[Long], end: Option[Long]): F[Asset[F]]
}
