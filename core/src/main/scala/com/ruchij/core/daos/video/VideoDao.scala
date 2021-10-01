package com.ruchij.core.daos.video

import cats.data.NonEmptyList
import com.ruchij.core.daos.scheduling.models.RangeValue
import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.daos.videometadata.models.VideoSite
import com.ruchij.core.services.models.{Order, SortBy}

import scala.concurrent.duration.FiniteDuration

trait VideoDao[F[_]] {
  def insert(videoMetadataId: String, videoFileResourceId: String, watchTime: FiniteDuration): F[Int]

  def search(
    term: Option[String],
    durationRange: RangeValue[FiniteDuration],
    sizeRange: RangeValue[Long],
    pageNumber: Int,
    pageSize: Int,
    sortBy: SortBy,
    order: Order,
    videoSites: Option[NonEmptyList[VideoSite]],
    maybeUserId: Option[String]
  ): F[Seq[Video]]

  def incrementWatchTime(videoId: String, finiteDuration: FiniteDuration): F[Option[FiniteDuration]]

  def findById(videoId: String, maybeUserId: Option[String]): F[Option[Video]]

  def findByVideoFileResourceId(fileResourceId: String): F[Option[Video]]

  def deleteById(videoId: String): F[Option[Video]]

  val count: F[Int]

  val duration: F[FiniteDuration]

  val size: F[Long]

  val sites: F[Set[VideoSite]]
}
