package com.ruchij.core.daos.video

import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.services.models.{Order, SortBy}
import com.ruchij.core.services.video.models.DurationRange

import scala.concurrent.duration.FiniteDuration

trait VideoDao[F[_]] {
  def insert(videoMetadataId: String, videoFileResourceId: String): F[Int]

  def search(
    term: Option[String],
    durationRange: DurationRange,
    pageNumber: Int,
    pageSize: Int,
    sortBy: SortBy,
    order: Order
  ): F[Seq[Video]]

  def findById(videoId: String): F[Option[Video]]

  def deleteById(videoId: String): F[Int]

  val count: F[Int]

  val duration: F[FiniteDuration]

  val size: F[Long]
}
