package com.ruchij.services.video

import com.ruchij.daos.resource.models.FileResource
import com.ruchij.daos.video.models.Video

trait VideoService[F[_]] {
  def insert(videoMetadataKey: String, fileResource: FileResource): F[Video]

  def fetchById(id: String): F[Video]

  def search(term: Option[String], pageNumber: Int, pageSize: Int): F[Seq[Video]]
}
