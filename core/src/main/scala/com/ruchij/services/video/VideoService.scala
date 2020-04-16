package com.ruchij.services.video

import com.ruchij.daos.video.models.Video
import com.ruchij.daos.videometadata.models.VideoMetadata
import fs2.Stream

trait VideoService[F[_]] {
  def insert(videoMetadata: VideoMetadata, path: String): F[Video]

  def fetchByKey(key: String): F[Video]

  def fetchResourceByVideoKey(key: String, start: Option[Long], end: Option[Long]): F[(Video, Stream[F, Byte])]

  def search(term: Option[String], pageNumber: Int, pageSize: Int): F[Seq[Video]]
}
