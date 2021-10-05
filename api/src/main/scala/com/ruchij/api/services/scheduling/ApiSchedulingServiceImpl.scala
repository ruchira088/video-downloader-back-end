package com.ruchij.api.services.scheduling

import cats.data.{NonEmptyList, OptionT}
import cats.effect.{Concurrent, Timer}
import cats.implicits._
import cats.{Applicative, ApplicativeError, MonadError, ~>}
import com.ruchij.api.daos.permission.VideoPermissionDao
import com.ruchij.api.daos.permission.models.VideoPermission
import com.ruchij.api.exceptions.ResourceConflictException
import com.ruchij.api.services.config.models.ApiConfigKey
import com.ruchij.core.daos.doobie.DoobieUtils.SingleUpdateOps
import com.ruchij.core.daos.scheduling.SchedulingDao
import com.ruchij.core.daos.scheduling.SchedulingDao.notFound
import com.ruchij.core.daos.scheduling.models.{RangeValue, ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.daos.title.VideoTitleDao
import com.ruchij.core.daos.title.models.VideoTitle
import com.ruchij.core.daos.videometadata.models.{CustomVideoSite, VideoMetadata, VideoSite}
import com.ruchij.core.daos.workers.models.WorkerStatus
import com.ruchij.core.exceptions.InvalidConditionException
import com.ruchij.core.logging.Logger
import com.ruchij.core.messaging.Publisher
import com.ruchij.core.services.config.ConfigurationService
import com.ruchij.core.services.models.{Order, SortBy}
import com.ruchij.core.services.scheduling.models.WorkerStatusUpdate
import com.ruchij.core.services.video.VideoAnalysisService
import com.ruchij.core.types.FunctionKTypes.{FunctionK2TypeOps, eitherToF}
import com.ruchij.core.types.JodaClock
import org.http4s.Uri

import scala.concurrent.duration.FiniteDuration

class ApiSchedulingServiceImpl[F[_]: Concurrent: Timer, T[_]: MonadError[*[_], Throwable]](
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

  override def schedule(uri: Uri, userId: String): F[ScheduledVideoDownload] =
    VideoSite
      .fromUri(uri)
      .toType[F, Throwable]
      .flatMap {
        case customVideoSite: CustomVideoSite => customVideoSite.processUri[F](uri)
        case _ => Applicative[F].pure(uri)
      }
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
        }
          .map(_.toList)
          .flatMap {
            case Nil => newScheduledVideoDownload(processedUri, userId)

            case scheduledVideoDownload :: _ =>
              existingScheduledVideoDownload(processedUri, scheduledVideoDownload.videoMetadata, userId)
                .as(scheduledVideoDownload)
          }
      }

  private def existingScheduledVideoDownload(uri: Uri, videoMetadata: VideoMetadata, userId: String): F[Unit] =
    for {
      timestamp <- JodaClock[F].timestamp

      result <- transaction {
        videoPermissionDao.find(Some(userId), Some(videoMetadata.id))
          .product(videoTitleDao.find(videoMetadata.id, userId))
          .flatMap { case (permissions, maybeTitle) =>
            if (permissions.isEmpty && maybeTitle.isEmpty)
              videoPermissionDao.insert(VideoPermission(timestamp, videoMetadata.id, userId)).one
                .productR {
                  videoTitleDao.insert(VideoTitle(videoMetadata.id, userId, videoMetadata.title)).one
                }
                .as((): Unit)
            else if (permissions.nonEmpty && maybeTitle.nonEmpty)
              ApplicativeError[T, Throwable]
                .raiseError[Unit](ResourceConflictException(s"$uri has already been scheduled"))
            else
              ApplicativeError[T, Throwable]
                .raiseError[Unit](InvalidConditionException(s"Video title and video permissions are in an invalid state for userId=$userId, videoId=${videoMetadata.id}"))
          }
      }
    } yield result

  private def newScheduledVideoDownload(uri: Uri, userId: String): F[ScheduledVideoDownload] =
    for {
      videoMetadataResult <- videoAnalysisService.metadata(uri)
      timestamp <- JodaClock[F].timestamp

      scheduledVideoDownload = ScheduledVideoDownload(
        timestamp,
        timestamp,
        SchedulingStatus.Queued,
        0,
        videoMetadataResult.value,
        None
      )

      _ <- transaction {
        schedulingDao.insert(scheduledVideoDownload).one
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
    } yield scheduledVideoDownload

  override def search(
    term: Option[String],
    videoUrls: Option[NonEmptyList[Uri]],
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
      transaction(
        schedulingDao.search(
          term,
          videoUrls,
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

  override def updateSchedulingStatus(id: String, status: SchedulingStatus): F[ScheduledVideoDownload] =
    JodaClock[F].timestamp
      .map { timestamp =>
        for {
          scheduledVideoDownload <- OptionT(schedulingDao.getById(id))
          _ <- OptionT.liftF(scheduledVideoDownload.status.validateTransition(status).toType[T, Throwable])
          updated <- OptionT(schedulingDao.updateSchedulingStatusById(id, status, timestamp))
        } yield updated
      }
      .flatMap { maybeUpdatedT =>
        OptionT(transaction(maybeUpdatedT.value))
          .getOrElseF { ApplicativeError[F, Throwable].raiseError(notFound(id)) }
      }
      .flatTap(scheduledVideoDownloadPublisher.publishOne)

  override def getById(id: String): F[ScheduledVideoDownload] =
    OptionT(transaction(schedulingDao.getById(id)))
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

  override def updateDownloadProgress(id: String, downloadedBytes: Long): F[ScheduledVideoDownload] =
    for {
      timestamp <- JodaClock[F].timestamp
      result <- OptionT {
        transaction { schedulingDao.updateDownloadProgress(id, downloadedBytes, timestamp) }
      }.getOrElseF(ApplicativeError[F, Throwable].raiseError(notFound(id)))
    } yield result
}
