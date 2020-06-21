package com.ruchij.daos.video

import com.ruchij.daos.video.models.Video
import com.ruchij.services.models.SortBy

trait VideoDao[F[_]] {
  def insert(videoMetadataId: String, videoFileResourceId: String): F[Int]

  def search(term: Option[String], pageNumber: Int, pageSize: Int, sortBy: SortBy): F[Seq[Video]]

  def findById(id: String): F[Option[Video]]
}
