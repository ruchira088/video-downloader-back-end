package com.ruchij.services.video

import com.ruchij.daos.resource.models.FileResource
import com.ruchij.daos.video.models.Video
import fs2.Stream

trait VideoService[F[_]] {
  def insert(videoMetadataKey: String, fileResource: FileResource): F[Video]

  def fetchByKey(key: String): F[Video]

  def fetchResourceByVideoKey(key: String, start: Option[Long], end: Option[Long]): F[(Video, Stream[F, Byte])]

  def search(term: Option[String], pageNumber: Int, pageSize: Int): F[Seq[Video]]
}
