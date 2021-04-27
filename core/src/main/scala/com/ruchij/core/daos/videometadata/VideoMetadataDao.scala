package com.ruchij.core.daos.videometadata

import com.ruchij.core.daos.videometadata.models.VideoMetadata
import org.http4s.Uri

trait VideoMetadataDao[F[_]] {
  def insert(videoMetadata: VideoMetadata): F[Int]

  def update(videoMetadataId: String, title: Option[String]): F[Int]

  def findById(videoMetadataId: String): F[Option[VideoMetadata]]

  def findByUrl(uri: Uri): F[Option[VideoMetadata]]

  def deleteById(videoMetadataId: String): F[Int]
}
