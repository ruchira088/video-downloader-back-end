package com.ruchij.daos.videometadata

import com.ruchij.daos.videometadata.models.VideoMetadata
import doobie.ConnectionIO

trait VideoMetadataDao[F[_]] {
  def insert(videoMetadata: VideoMetadata): ConnectionIO[Int]

  def add(videoMetadata: VideoMetadata): F[Int]
}
