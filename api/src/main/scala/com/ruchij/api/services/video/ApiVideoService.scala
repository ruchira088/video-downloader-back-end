package com.ruchij.api.services.video

import cats.data.NonEmptyList
import com.ruchij.api.services.video.models.VideoScanProgress
import com.ruchij.core.daos.scheduling.models.RangeValue
import com.ruchij.core.daos.snapshot.models.Snapshot
import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.daos.videometadata.models.VideoSite
import com.ruchij.core.services.models.{Order, SortBy}
import com.ruchij.core.services.video.models.VideoServiceSummary
import org.http4s.Uri

import scala.concurrent.duration.FiniteDuration

trait ApiVideoService[F[_]] {
  def fetchById(videoId: String, maybeUserId: Option[String]): F[Video]

  def fetchVideoSnapshots(videoId: String, maybeUserId: Option[String]): F[Seq[Snapshot]]

  def update(videoId: String, title: String, maybeUserId: Option[String]): F[Video]

  def deleteById(videoId: String, maybeUserId: Option[String], deleteVideoFile: Boolean): F[Video]

  def search(
    term: Option[String],
    videoUrls: Option[NonEmptyList[Uri]],
    durationRange: RangeValue[FiniteDuration],
    sizeRange: RangeValue[Long],
    pageNumber: Int,
    pageSize: Int,
    sortBy: SortBy,
    order: Order,
    videoSites: Option[NonEmptyList[VideoSite]],
    maybeUserId: Option[String]
  ): F[Seq[Video]]

  val summary: F[VideoServiceSummary]

  val scanForVideos: F[VideoScanProgress]

  val queueIncorrectlyCompletedVideos: F[Seq[Video]]
}
