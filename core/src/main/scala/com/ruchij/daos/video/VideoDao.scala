package com.ruchij.daos.video

import com.ruchij.daos.resource.models.FileResource
import com.ruchij.daos.video.models.Video

trait VideoDao[F[_]] {
  def insert(videoMetadataKey: String, fileResource: FileResource): F[Int]

  def search(term: Option[String], pageNumber: Int, pageSize: Int): F[Seq[Video]]

  def findByKey(key: String): F[Option[Video]]
}
