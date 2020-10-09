package com.ruchij.core.services.scheduling

import cats.data.{NonEmptyList, OptionT}
import cats.effect.{Sync, Timer}
import cats.implicits._
import cats.{Applicative, ApplicativeError, Monad, ~>}
import com.ruchij.core.daos.scheduling.SchedulingDao
import com.ruchij.core.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.core.exceptions.{InvalidConditionException, ResourceConflictException, ResourceNotFoundException}
import com.ruchij.core.kv.KeySpacedKeyValueStore
import com.ruchij.core.services.models.{Order, SortBy}
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

      _ <-
        if (searchResult.nonEmpty)
          ApplicativeError[F, Throwable].raiseError(ResourceConflictException(s"$uri has already been scheduled"))
        else Applicative[F].unit

      videoMetadataResult <- videoAnalysisService.metadata(uri)
      timestamp <- JodaClock[F].timestamp

      scheduledVideoDownload = ScheduledVideoDownload(timestamp, videoMetadataResult.value, None)

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
        ApplicativeError[F, Throwable].raiseError(
          ResourceNotFoundException(s"Unable to find scheduled video download with ID = $id")
        )
      }

  override def updateDownloadProgress(id: String, downloadedBytes: Long): F[Unit] =
    for {
      timestamp <- JodaClock[F].timestamp

      result <- keySpacedKeyValueStore.put(DownloadProgressKey(id), DownloadProgress(id, timestamp, downloadedBytes))
    } yield result

  override def completeTask(id: String): F[ScheduledVideoDownload] =
    JodaClock[F].timestamp
      .flatMap { timestamp =>
        OptionT(transaction(schedulingDao.completeTask(id, timestamp)))
          .getOrElseF(ApplicativeError[F, Throwable].raiseError(InvalidConditionException))
      }

  override val acquireTask: OptionT[F, ScheduledVideoDownload] =
    OptionT {
      transaction(schedulingDao.retrieveTask)
    }

  override val active: Stream[F, DownloadProgress] =
    Stream
      .awakeDelay[F](500 milliseconds)
      .productR {
        Stream.eval(JodaClock[F].timestamp)
      }
      .scan[(Option[DateTime], Option[DateTime])]((None, None)) {
        case ((_, Some(previous)), timestamp) => (Some(previous), Some(timestamp))
        case (_, timestamp) => (None, Some(timestamp))
      }
      .collect {
        case (Some(start), Some(end)) => (start, end)
      }
      .evalMap {
        case (start, end) =>
          keySpacedKeyValueStore.allKeys
            .flatMap(_.traverse(keySpacedKeyValueStore.get))
            .map {
              _.collect { case Some(value) => value }
                .filter {
                  case DownloadProgress(_, updatedAt, _) => updatedAt.isAfter(start) && updatedAt.isBefore(end)
                }
            }
      }
      .flatMap { results =>
        Stream.emits(results)
      }
}
