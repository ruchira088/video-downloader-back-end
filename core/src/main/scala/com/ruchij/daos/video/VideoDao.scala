package com.ruchij.daos.video

import com.ruchij.daos.video.models.Video
import com.ruchij.services.models.{Order, SortBy}

trait VideoDao[F[_]] {
  def insert(videoMetadataId: String, videoFileResourceId: String): F[Int]

  def search(term: Option[String], pageNumber: Int, pageSize: Int, sortBy: SortBy, order: Order): F[Seq[Video]]

  def findById(videoId: String): F[Option[Video]]

  def deleteById(videoId: String): F[Int]
}
