package com.ruchij.core.services.asset.models

import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.services.asset.models.Asset.FileRange
import fs2.Stream

final case class Asset[F[_]](fileResource: FileResource, stream: Stream[F, Byte], fileRange: FileRange)

object Asset {
  final case class FileRange(start: Long, end: Long)
}
