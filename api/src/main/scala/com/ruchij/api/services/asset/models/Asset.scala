package com.ruchij.api.services.asset.models

import com.ruchij.api.services.asset.models.Asset.FileRange
import com.ruchij.core.daos.resource.models.FileResource
import fs2.Stream

final case class Asset[F[_], A <: AssetType](
  fileResource: FileResource,
  stream: Stream[F, Byte],
  fileRange: FileRange
)

object Asset {
  final case class FileRange(start: Long, end: Long)
}
