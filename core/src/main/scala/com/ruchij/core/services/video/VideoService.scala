package com.ruchij.core.services.video

import cats.data.NonEmptyList
import com.ruchij.core.daos.snapshot.models.Snapshot
import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.daos.videometadata.models.VideoSite
import com.ruchij.core.services.models.{Order, SortBy}
import com.ruchij.core.services.video.models.{DurationRange, VideoServiceSummary}

import scala.concurrent.duration.FiniteDuration

trait VideoService[F[_]] {
  def insert(videoMetadataKey: String, fileResourceKey: String): F[Video]

  def fetchById(videoId: String): F[Video]

  def fetchVideoSnapshots(videoId: String): F[Seq[Snapshot]]

  def fetchByVideoFileResourceId(videoFileResourceId: String): F[Video]

  def incrementWatchTime(videoId: String, duration: FiniteDuration): F[FiniteDuration]

  def update(videoId: String, maybeTitle: Option[String], maybeSize: Option[Long]): F[Video]

  def deleteById(videoId: String, deleteVideoFile: Boolean): F[Video]

  def search(
    term: Option[String],
    durationRange: DurationRange,
    pageNumber: Int,
    pageSize: Int,
    sortBy: SortBy,
    order: Order,
    videoSites: Option[NonEmptyList[VideoSite]]
  ): F[Seq[Video]]

  val summary: F[VideoServiceSummary]
}
