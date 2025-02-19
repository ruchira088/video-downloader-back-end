package com.ruchij.api.services.scheduling

import cats.data.{NonEmptyList, OptionT}
import cats.effect.Async
import cats.implicits._
import cats.{Applicative, ApplicativeError, MonadThrow, ~>}
import com.ruchij.api.services.config.models.ApiConfigKey
import com.ruchij.api.services.scheduling.models.ScheduledVideoResult
import com.ruchij.core.daos.doobie.DoobieUtils.SingleUpdateOps
import com.ruchij.core.daos.permission.VideoPermissionDao
import com.ruchij.core.daos.permission.models.VideoPermission
import com.ruchij.core.daos.scheduling.SchedulingDao
import com.ruchij.core.daos.scheduling.SchedulingDao.notFound
import com.ruchij.core.daos.scheduling.models.{RangeValue, ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.daos.title.VideoTitleDao
import com.ruchij.core.daos.title.models.VideoTitle
import com.ruchij.core.daos.videometadata.models.{VideoMetadata, VideoSite}
import com.ruchij.core.daos.workers.models.WorkerStatus
import com.ruchij.core.exceptions.{InvalidConditionException, ValidationException}
import com.ruchij.core.logging.Logger
import com.ruchij.core.messaging.Publisher
import com.ruchij.core.services.config.ConfigurationService
import com.ruchij.core.services.models.{Order, SortBy}
import com.ruchij.core.services.scheduling.models.WorkerStatusUpdate
import com.ruchij.core.services.video.{VideoAnalysisService, VideoService}
import com.ruchij.core.types.JodaClock
import org.http4s.Uri
import org.joda.time.DateTime

import scala.concurrent.duration.FiniteDuration

class ApiSchedulingServiceImpl[F[_]: Async: JodaClock, T[_]: MonadThrow](
  videoService: VideoService[F, T],
  videoAnalysisService: VideoAnalysisService[F],
  scheduledVideoDownloadPublisher: Publisher[F, ScheduledVideoDownload],
  workerStatusPublisher: Publisher[F, WorkerStatusUpdate],
  configurationService: ConfigurationService[F, ApiConfigKey],
  schedulingDao: SchedulingDao[T],
  videoTitleDao: VideoTitleDao[T],
  videoPermissionDao: VideoPermissionDao[T]
)(implicit transaction: T ~> F)
    extends ApiSchedulingService[F] {

  private val logger = Logger[ApiSchedulingServiceImpl[F, T]]

  override def schedule(uri: Uri, userId: String): F[ScheduledVideoResult] =
    VideoSite
      .processUri[F](uri)
      .flatMap { processedUri =>
        transaction {
          schedulingDao.search(
            None,
            Some(NonEmptyList.of(processedUri)),
            RangeValue.all[FiniteDuration],
            RangeValue.all[Long],
            0,
            1,
            SortBy.Date,
            Order.Descending,
            None,
            None,
            None
          )
        }.map(_.toList)
          .flatMap {
            case Nil => newScheduledVideoDownload(processedUri, userId).map(identity[ScheduledVideoResult])

            case scheduledVideoDownload :: _ =>
              existingScheduledVideoDownload(scheduledVideoDownload.videoMetadata, userId)
                .map { created =>
                  if (created) ScheduledVideoResult.NewlyScheduled(scheduledVideoDownload)
                  else ScheduledVideoResult.AlreadyScheduled(scheduledVideoDownload)
                }
          }
      }

  private def existingScheduledVideoDownload(videoMetadata: VideoMetadata, userId: String): F[Boolean] =
    for {
      timestamp <- JodaClock[F].timestamp

      result <- transaction {
        videoPermissionDao
          .find(Some(userId), Some(videoMetadata.id))
          .product(videoTitleDao.find(videoMetadata.id, userId))
          .flatMap {
            case (permissions, maybeTitle) =>
              if (permissions.isEmpty && maybeTitle.isEmpty)
                videoPermissionDao
                  .insert(VideoPermission(timestamp, videoMetadata.id, userId))
                  .one
                  .productR {
                    videoTitleDao.insert(VideoTitle(videoMetadata.id, userId, videoMetadata.title)).one
                  }
                  .as(true)
              else if (permissions.nonEmpty && maybeTitle.nonEmpty) Applicative[T].pure(false)
              else
                ApplicativeError[T, Throwable]
                  .raiseError[Boolean](
                    InvalidConditionException(
                      s"Video title and video permissions are in an invalid state for userId=$userId, videoId=${videoMetadata.id}"
                    )
                  )
          }
      }
    } yield result

  private def newScheduledVideoDownload(uri: Uri, userId: String): F[ScheduledVideoResult.NewlyScheduled] =
    for {
      videoMetadataResult <- videoAnalysisService.metadata(uri)
      timestamp <- JodaClock[F].timestamp

      scheduledVideoDownload = ScheduledVideoDownload(
        timestamp,
        timestamp,
        SchedulingStatus.Queued,
        0,
        videoMetadataResult.value,
        None,
        None
      )

      _ <- transaction {
        schedulingDao
          .insert(scheduledVideoDownload)
          .one
          .productR {
            videoTitleDao.insert {
              VideoTitle(scheduledVideoDownload.videoMetadata.id, userId, scheduledVideoDownload.videoMetadata.title)
            }
          }
          .product {
            videoPermissionDao.insert(VideoPermission(timestamp, scheduledVideoDownload.videoMetadata.id, userId))
          }
      }
      _ <- logger.info(s"Scheduled to download video at $uri")

      _ <- scheduledVideoDownloadPublisher.publishOne(scheduledVideoDownload)
    } yield ScheduledVideoResult.NewlyScheduled(scheduledVideoDownload)

  override def search(
    term: Option[String],
    maybeVideoUrls: Option[NonEmptyList[Uri]],
    durationRange: RangeValue[FiniteDuration],
    sizeRange: RangeValue[Long],
    pageNumber: Int,
    pageSize: Int,
    sortBy: SortBy,
    order: Order,
    schedulingStatuses: Option[NonEmptyList[SchedulingStatus]],
    videoSites: Option[NonEmptyList[VideoSite]],
    maybeUserId: Option[String]
  ): F[Seq[ScheduledVideoDownload]] =
    if (sortBy == SortBy.WatchTime)
      ApplicativeError[F, Throwable].raiseError {
        new IllegalArgumentException("Searching for scheduled videos by watch_time is not valid")
      } else
      maybeVideoUrls
        .traverse(_.traverse(videoUrl => VideoSite.processUri[F](videoUrl)))
        .flatMap { maybeProcessedUrls =>
          transaction(
            schedulingDao.search(
              term,
              maybeProcessedUrls,
              durationRange,
              sizeRange,
              pageNumber,
              pageSize,
              sortBy,
              order,
              schedulingStatuses,
              videoSites,
              maybeUserId
            )
          )
        }

  override def updateSchedulingStatus(id: String, status: SchedulingStatus): F[ScheduledVideoDownload] =
    JodaClock[F].timestamp
      .flatMap { timestamp =>
        OptionT(transaction(schedulingDao.updateSchedulingStatusById(id, status, timestamp))).getOrElseF {
          ApplicativeError[F, Throwable].raiseError(notFound(id))
        }
      }
      .flatTap(scheduledVideoDownloadPublisher.publishOne)

  override def getById(id: String, maybeUserId: Option[String]): F[ScheduledVideoDownload] =
    OptionT(transaction(schedulingDao.getById(id, maybeUserId)))
      .getOrElseF {
        ApplicativeError[F, Throwable].raiseError(notFound(id))
      }

  override def updateWorkerStatus(workerStatus: WorkerStatus): F[Unit] =
    configurationService
      .put(ApiConfigKey.WorkerStatus, workerStatus)
      .productR {
        workerStatusPublisher.publishOne(WorkerStatusUpdate(workerStatus))
      }

  override val getWorkerStatus: F[WorkerStatus] =
    configurationService
      .get[WorkerStatus, ApiConfigKey](ApiConfigKey.WorkerStatus)
      .map(_.getOrElse(WorkerStatus.Available))

  override def updateDownloadProgress(
    id: String,
    timestamp: DateTime,
    downloadedBytes: Long
  ): F[ScheduledVideoDownload] =
    OptionT { transaction { schedulingDao.updateDownloadProgress(id, downloadedBytes, timestamp) }}
      .getOrElseF {
        logger.warn(s"Ignored download progress update for id=$id, timestamp=$timestamp, downloadedBytes=$downloadedBytes")
          .productR(getById(id, None))
      }


  override def deleteById(id: String, maybeUserId: Option[String]): F[ScheduledVideoDownload] =
    transaction {
      OptionT(schedulingDao.getById(id, maybeUserId))
        .semiflatTap { scheduledVideoDownload =>
          if (List(SchedulingStatus.Completed, SchedulingStatus.Downloaded).contains(scheduledVideoDownload.status))
            ApplicativeError[T, Throwable].raiseError[Int] {
              ValidationException("Unable to delete scheduled video downloads that are completed or downloaded")
            } else Applicative[T].pure(0)
        }
        .getOrElseF(ApplicativeError[T, Throwable].raiseError(notFound(id)))
    }.flatMap { scheduledVideoDownload =>
      if (maybeUserId.isEmpty) {
        JodaClock[F].timestamp.flatMap { timestamp =>
          val deleted = scheduledVideoDownload.copy(lastUpdatedAt = timestamp, status = SchedulingStatus.Deleted)

          ApplicativeError[F, Throwable]
            .handleError {
              videoService
                .deleteById(scheduledVideoDownload.videoMetadata.id, deleteVideoFile = false)
                .as(1)
            }(_ => 0)
            .productR {
              scheduledVideoDownloadPublisher.publishOne(deleted).as(deleted)
            }
        }
      } else Applicative[F].pure(scheduledVideoDownload)
    }
}
