package com.ruchij.core.daos.video

import cats.data.NonEmptyList
import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.services.models.{Order, SortBy}
import org.http4s.Uri

import scala.concurrent.duration.FiniteDuration

trait VideoDao[F[_]] {
  def insert(videoMetadataId: String, videoFileResourceId: String): F[Int]

  def search(
    term: Option[String],
    videoUrls: Option[NonEmptyList[Uri]],
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
