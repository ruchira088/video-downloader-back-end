package com.ruchij.core.services.scheduling

import cats.data.{NonEmptyList, OptionT}
import cats.effect.{Sync, Timer}
import cats.implicits._
import cats.{Applicative, ApplicativeError, Monad, ~>}
import com.ruchij.core.daos.scheduling.SchedulingDao
import com.ruchij.core.daos.scheduling.models.{ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.exceptions.{ResourceConflictException, ResourceNotFoundException}
import com.ruchij.core.kv.KeySpacedKeyValueStore
import com.ruchij.core.services.models.{Order, SortBy}
import com.ruchij.core.services.scheduling.SchedulingServiceImpl.notFound
import com.ruchij.core.services.scheduling.models.DownloadProgress
import com.ruchij.core.services.scheduling.models.DownloadProgress.DownloadProgressKey
import com.ruchij.core.services.video.VideoAnalysisService
import com.ruchij.core.types.JodaClock
import fs2.Stream
import org.http4s.Uri
import org.joda.time.DateTime

import scala.concurrent.duration._
import scala.language.postfixOps

class SchedulingServiceImpl[F[_]: Sync: Timer, T[_]: Monad](
  videoAnalysisService: VideoAnalysisService[F],
  schedulingDao: SchedulingDao[T],
  keySpacedKeyValueStore: KeySpacedKeyValueStore[F, DownloadProgressKey, DownloadProgress],
)(implicit transaction: T ~> F)
    extends SchedulingService[F] {

  override def schedule(uri: Uri): F[ScheduledVideoDownload] =
    for {
      searchResult <- transaction {
        schedulingDao.search(None, Some(NonEmptyList.of(uri)), 0, 1, SortBy.Date, Order.Descending)
      }

      _ <- if (searchResult.nonEmpty)
        ApplicativeError[F, Throwable].raiseError(ResourceConflictException(s"$uri has already been scheduled"))
      else Applicative[F].unit

      videoMetadataResult <- videoAnalysisService.metadata(uri)
      timestamp <- JodaClock[F].timestamp

      scheduledVideoDownload = ScheduledVideoDownload(
        timestamp,
        timestamp,
        SchedulingStatus.Queued,
        videoMetadataResult.value,
        None
      )

      _ <- transaction(schedulingDao.insert(scheduledVideoDownload))
    } yield scheduledVideoDownload

  override def search(
    term: Option[String],
    videoUrls: Option[NonEmptyList[Uri]],
    pageNumber: Int,
    pageSize: Int,
    sortBy: SortBy,
    order: Order
  ): F[Seq[ScheduledVideoDownload]] =
    transaction {
      schedulingDao.search(term, videoUrls, pageNumber, pageSize, sortBy, order)
    }

  override def getById(id: String): F[ScheduledVideoDownload] =
    OptionT(transaction(schedulingDao.getById(id)))
      .getOrElseF {
        ApplicativeError[F, Throwable].raiseError(notFound(id))
      }

  override def completeTask(id: String): F[ScheduledVideoDownload] =
    JodaClock[F].timestamp
      .flatMap { timestamp =>
        OptionT(transaction(schedulingDao.completeTask(id, timestamp)))
          .getOrElseF(ApplicativeError[F, Throwable].raiseError(notFound(id)))
      }

  override def updateStatus(id: String, status: SchedulingStatus): F[ScheduledVideoDownload] =
    for {
      timestamp <- JodaClock[F].timestamp
      scheduledVideoDownload <- getById(id)

      _ <- if (scheduledVideoDownload.status.validTransitionStatuses.contains(status))
        Applicative[F].unit
      else
        ApplicativeError[F, Throwable].raiseError {
          new IllegalArgumentException(s"Transition not valid: ${scheduledVideoDownload.status} -> $status")
        }

      updatedScheduledVideoDownload <- OptionT(transaction(schedulingDao.updateStatus(id, status, timestamp)))
        .getOrElseF(ApplicativeError[F, Throwable].raiseError(notFound(id)))
    } yield updatedScheduledVideoDownload

  override val acquireTask: OptionT[F, ScheduledVideoDownload] =
    OptionT {
      transaction(schedulingDao.retrieveTask)
    }

  override val updates: Stream[F, ScheduledVideoDownload] =
    timeInterval(1 second).zipWithNext
      .collect { case (start, Some(end)) => start -> end }
      .evalMap { case (start, end) => transaction(schedulingDao.updatedBetween(start, end)) }
      .flatMap(Stream.emits[F, ScheduledVideoDownload])

  override def updateDownloadProgress(id: String, downloadedBytes: Long): F[Unit] =
    for {
      timestamp <- JodaClock[F].timestamp

      result <- keySpacedKeyValueStore.put(DownloadProgressKey(id), DownloadProgress(id, timestamp, downloadedBytes))
    } yield result

  override val downloadProgress: Stream[F, DownloadProgress] =
    timeInterval(500 milliseconds)
      .evalMap { start =>
        keySpacedKeyValueStore.allKeys
          .flatMap(_.traverse(keySpacedKeyValueStore.get))
          .map {
            _.collect { case Some(value) => value }
              .filter {
                case DownloadProgress(_, updatedAt, _) => updatedAt.isAfter(start)
              }
          }
      }
      .flatMap { results =>
        Stream.emits(results)
      }

  def timeInterval(interval: FiniteDuration): Stream[F, DateTime] =
    Stream
      .fixedRate[F](interval)
      .productR(Stream.eval(JodaClock[F].timestamp))
      .map(_.minus(2 * interval.toMillis))

}

object SchedulingServiceImpl {
  def notFound(id: String): ResourceNotFoundException =
    ResourceNotFoundException(s"Unable to find scheduled video download with ID = $id")
}
