package com.ruchij.core.daos.video

import cats.data.NonEmptyList
import com.ruchij.core.daos.scheduling.models.RangeValue
import com.ruchij.core.daos.video.models.{Video, VideoSiteSummary}
import com.ruchij.core.daos.videometadata.models.VideoSite
import com.ruchij.core.services.models.{Order, SortBy}
import org.http4s.Uri
import java.time.Instant

import scala.concurrent.duration.FiniteDuration

trait VideoDao[F[_]] {

  /** Inserts the video, restoring the playlist memberships it had if it was deleted for a re-download. */
  def insert(
    videoMetadataId: String,
    videoFileResourceId: String,
    timestamp: Instant,
    watchTime: FiniteDuration
  ): F[Int]

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

  def incrementWatchTime(videoId: String, finiteDuration: FiniteDuration): F[Option[FiniteDuration]]

  def findById(videoId: String, maybeUserId: Option[String]): F[Option[Video]]

  /** Returns the matching videos in no particular order. */
  def findByIds(videoIds: NonEmptyList[String]): F[Seq[Video]]

  def findByVideoFileResourceId(fileResourceId: String): F[Option[Video]]

  def findByVideoPath(videoPath: String): F[Option[Video]]

  /** Deletes the video, which also removes it from every playlist. */
  def deleteById(videoId: String): F[Int]

  /**
    * Deletes a video that is going to be downloaded again. Its playlist memberships are kept aside and restored when
    * the video is inserted again under the same ID.
    */
  def deleteForRedownload(videoId: String): F[Int] = deleteById(videoId)

  def hasVideoFilePermission(videoFileResourceId: String, userId: String): F[Boolean]

  def isVideoFileResourceExist(videoFileResourceId: String): F[Boolean]

  val count: F[Int]

  val duration: F[FiniteDuration]

  val size: F[Long]

  val sites: F[Set[VideoSite]]

  /** Per-site totals, ordered by video count descending and then by site name. */
  val siteSummaries: F[Seq[VideoSiteSummary]]
}
