package com.ruchij.services.video

import com.ruchij.daos.video.models.Video
import com.ruchij.daos.videometadata.models.VideoMetadata

trait VideoService[F[_]] {
  def insert(videoMetadata: VideoMetadata, path: String): F[Video]

  def search(term: Option[String], pageNumber: Int, pageSize: Int): F[Seq[Video]]
}
