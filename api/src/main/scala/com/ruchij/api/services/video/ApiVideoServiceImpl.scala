package com.ruchij.api.services.video

import cats.data.NonEmptyList
import cats.implicits._
import cats.{MonadThrow, ~>}
import com.ruchij.api.daos.permission.VideoPermissionDao
import com.ruchij.api.daos.title.VideoTitleDao
import com.ruchij.api.services.video.models.VideoScanProgress
import com.ruchij.core.commands.ScanVideosCommand
import com.ruchij.core.daos.doobie.DoobieUtils.SingleUpdateOps
import com.ruchij.core.daos.scheduling.SchedulingDao
import com.ruchij.core.daos.scheduling.models.RangeValue
import com.ruchij.core.daos.snapshot.SnapshotDao
import com.ruchij.core.daos.snapshot.models.Snapshot
import com.ruchij.core.daos.video.VideoDao
import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.daos.videometadata.VideoMetadataDao
import com.ruchij.core.daos.videometadata.models.VideoSite
import com.ruchij.core.messaging.Publisher
import com.ruchij.core.services.models.{Order, SortBy}
import com.ruchij.core.services.video.VideoService
import com.ruchij.core.services.video.models.VideoServiceSummary
import com.ruchij.core.types.JodaClock
import org.http4s.Uri

import scala.concurrent.duration.FiniteDuration

class ApiVideoServiceImpl[F[_]: MonadThrow: JodaClock, G[_]: MonadThrow](
  videoService: VideoService[F, G],
  videoScanPublisher: Publisher[F, ScanVideosCommand],
  videoDao: VideoDao[G],
  schedulingDao: SchedulingDao[G],
  videoMetadataDao: VideoMetadataDao[G],
  snapshotDao: SnapshotDao[G],
  videoTitleDao: VideoTitleDao[G],
  videoPermissionDao: VideoPermissionDao[G],
)(implicit transaction: G ~> F)
    extends ApiVideoService[F] {

  override def fetchById(videoId: String, maybeUserId: Option[String]): F[Video] =
    transaction(videoService.findVideoById(videoId, maybeUserId))

  override def fetchVideoSnapshots(videoId: String, maybeUserId: Option[String]): F[Seq[Snapshot]] =
    transaction {
      snapshotDao.findByVideo(videoId, maybeUserId)
    }

  override def update(videoId: String, title: String, maybeUserId: Option[String]): F[Video] =
    transaction {
      videoService
        .findVideoById(videoId, maybeUserId)
        .productR {
          maybeUserId.fold(videoMetadataDao.update(videoId, Some(title), None, None).one) { userId =>
            videoTitleDao.update(videoId, userId, title).one
          }
        }
        .productR(videoService.findVideoById(videoId, maybeUserId))
    }

  override def deleteById(videoId: String, maybeUserId: Option[String], deleteVideoFile: Boolean): F[Video] =
    maybeUserId match {
      case Some(userId) =>
        transaction {
          videoService
            .findVideoById(videoId, Some(userId))
            .productL(videoTitleDao.delete(Some(videoId), Some(userId)))
            .productL(videoPermissionDao.delete(Some(userId), Some(videoId)))
        }

      case None =>
        videoService.deleteById(videoId, deleteVideoFile) { _ =>
          videoTitleDao
            .delete(Some(videoId), None)
            .productR(videoPermissionDao.delete(None, Some(videoId)))
            .productR(schedulingDao.deleteById(videoId))
            .as((): Unit)
        }
    }

  override def search(
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
  ): F[Seq[Video]] =
    transaction {
      videoDao.search(
        term,
        videoUrls,
        durationRange,
        sizeRange,
        pageNumber,
        pageSize,
        sortBy,
        order,
        videoSites,
        maybeUserId
      )
    }

  override val summary: F[VideoServiceSummary] =
    transaction {
      for {
        count <- videoDao.count
        size <- videoDao.size
        duration <- videoDao.duration
        sites <- videoDao.sites
      } yield VideoServiceSummary(count, size, duration, sites)
    }

  override val scanForVideos: F[VideoScanProgress] =
    JodaClock[F].timestamp
      .flatMap { timestamp =>
        videoScanPublisher.publishOne(ScanVideosCommand(timestamp))
          .as(VideoScanProgress.ScanStarted(timestamp))
      }
}
