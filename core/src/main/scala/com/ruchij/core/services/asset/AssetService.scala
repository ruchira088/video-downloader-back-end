package com.ruchij.core.services.asset

import com.ruchij.core.services.asset.models.Asset

trait AssetService[F[_]] {
  def retrieve(id: String, start: Option[Long], end: Option[Long]): F[Asset[F]]
}
