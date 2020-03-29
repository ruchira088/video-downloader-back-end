package com.ruchij.daos.videometadata

import com.ruchij.daos.videometadata.models.VideoMetadata

trait VideoMetadataDao[F[_]] {
  def insert(videoMetadata: VideoMetadata): F[Int]
}
