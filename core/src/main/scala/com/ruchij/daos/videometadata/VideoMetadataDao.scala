package com.ruchij.daos.videometadata

import com.ruchij.daos.videometadata.models.VideoMetadata

trait VideoMetadataDao[F[_]] {
  def insert(videoMetadata: VideoMetadata): F[Int]

  def update(videoId: String, title: Option[String]): F[Int]

  def getById(videoId: String): F[Option[VideoMetadata]]
}
