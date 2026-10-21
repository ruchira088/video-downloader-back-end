package com.ruchij.core.daos.scheduling

import cats.data.{NonEmptyList, OptionT}
import cats.implicits._
import cats.{Applicative, ApplicativeError}
import com.ruchij.core.daos.doobie.DoobieCustomMappings._
import com.ruchij.core.daos.doobie.DoobieUtils.{SingleUpdateOps, ordering, sortByFieldName}
import com.ruchij.core.daos.scheduling.models.{RangeValue, ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.daos.videometadata.models.VideoSite
import com.ruchij.core.services.models.{Order, SortBy}
import doobie.free.connection.ConnectionIO
import doobie.implicits._
import doobie.util.fragment.Fragment
import doobie.util.fragments.{in, whereAndOpt}
import org.http4s.Uri
import java.time.Instant

import scala.concurrent.duration.FiniteDuration

object DoobieSchedulingDao extends SchedulingDao[ConnectionIO] {
  private val RetryPageSize = 50

  override def insert(scheduledVideoDownload: ScheduledVideoDownload): ConnectionIO[Int] =
    scheduledVideoDownload.errorInfo match {
      case None =>
        sql"""
      INSERT INTO scheduled_video (scheduled_at, last_updated_at, status, downloaded_bytes, video_metadata_id, completed_at)
        VALUES (
          ${scheduledVideoDownload.scheduledAt},
          ${scheduledVideoDownload.lastUpdatedAt},
          ${scheduledVideoDownload.status},
          ${scheduledVideoDownload.downloadedBytes},
          ${scheduledVideoDownload.videoMetadata.id},
          ${scheduledVideoDownload.completedAt}
        )
        ON CONFLICT DO NOTHING
     """.update.run

      case _ =>
        ApplicativeError[ConnectionIO, Throwable].raiseError {
          new IllegalArgumentException("It is not valid to insert a ScheduledVideoDownload with an error")
        }
    }

  val SelectQuery: Fragment =
    fr"""
      SELECT
        scheduled_video.scheduled_at, scheduled_video.last_updated_at, scheduled_video.status,
        scheduled_video.downloaded_bytes, video_metadata.url, video_metadata.id, video_metadata.video_site,
        video_metadata.title, video_metadata.duration, video_metadata.size, file_resource.id,
        file_resource.created_at, file_resource.path, file_resource.media_type, file_resource.size,
        scheduled_video.completed_at, scheduled_video_error.error_message, scheduled_video_error.error_details,
        scheduled_video.retry_count, scheduled_video.retry_after
      FROM scheduled_video
      INNER JOIN video_metadata ON scheduled_video.video_metadata_id = video_metadata.id
      INNER JOIN file_resource ON video_metadata.thumbnail_id = file_resource.id
      LEFT JOIN scheduled_video_error ON scheduled_video.error_id = scheduled_video_error.id
    """

  override def getById(id: String, maybeUserId: Option[String]): ConnectionIO[Option[ScheduledVideoDownload]] =
    (SelectQuery ++ (if (maybeUserId.isEmpty) Fragment.empty
                     else fr"INNER JOIN permission ON video_metadata.id = permission.video_id") ++
      whereAndOpt(
        Some(fr"scheduled_video.video_metadata_id = $id"),
        maybeUserId.map(userId => fr"permission.user_id = $userId")
      ))
      .query[ScheduledVideoDownload]
      .option

  override def markScheduledVideoDownloadAsComplete(
    id: String,
    timestamp: Instant
  ): ConnectionIO[Option[ScheduledVideoDownload]] =
    sql"""
        UPDATE scheduled_video
          SET
            completed_at = $timestamp,
            status = ${SchedulingStatus.Completed},
            last_updated_at = $timestamp,
            error_id = NULL,
            retry_count = 0,
            retry_after = NULL
          WHERE
            completed_at IS NULL AND
            video_metadata_id = $id
      """.update.run.singleUpdate
      .productR(OptionT(getById(id, None)))
      .value

  override def updateSchedulingStatusById(
    id: String,
    status: SchedulingStatus,
    timestamp: Instant
  ): ConnectionIO[Option[ScheduledVideoDownload]] =
    sql"""
        UPDATE scheduled_video
          SET
            status = $status,
            last_updated_at = $timestamp,
            error_id = NULL,
            retry_count = CASE WHEN status = ${SchedulingStatus.Error} THEN 0 ELSE retry_count END,
            retry_after = NULL
          WHERE video_metadata_id = $id
       """.update.run.singleUpdate
      .productR(OptionT(getById(id, None)))
      .value

  override def transitionSchedulingStatusById(
    id: String,
    from: NonEmptyList[SchedulingStatus],
    to: SchedulingStatus,
    timestamp: Instant,
    maybeSoleUserId: Option[String]
  ): ConnectionIO[Option[ScheduledVideoDownload]] =
    // Both conditions are part of the UPDATE, so a concurrent status change cannot slip in between a check and it
    (fr"""
        UPDATE scheduled_video
          SET
            status = $to,
            last_updated_at = $timestamp,
            error_id = NULL,
            retry_count = CASE WHEN status = ${SchedulingStatus.Error} THEN 0 ELSE retry_count END,
            retry_after = NULL
       """ ++
      whereAndOpt(
        Some(fr"video_metadata_id = $id"),
        Some(in(fr"status", from)),
        maybeSoleUserId.map { userId =>
          fr"NOT EXISTS (SELECT 1 FROM permission WHERE permission.video_id = $id AND permission.user_id <> $userId)"
        }
      )).update.run.singleUpdate
      .productR(OptionT(getById(id, None)))
      .value

  override def updateSchedulingStatus(
    from: SchedulingStatus,
    to: SchedulingStatus
  ): ConnectionIO[Seq[ScheduledVideoDownload]] =
    sql"SELECT video_metadata_id FROM scheduled_video WHERE status = $from"
      .query[String]
      .to[List]
      .flatMap(ids => transitionSchedulingStatus(ids, from, to))

  private[daos] def transitionSchedulingStatus(
    ids: List[String],
    from: SchedulingStatus,
    to: SchedulingStatus
  ): ConnectionIO[Seq[ScheduledVideoDownload]] =
    // The status is checked again, as the row can change between the SELECT and the UPDATE. Updating the rows one
    // by one tells which of them actually changed, so only those are returned.
    ids
      .filterA { id =>
        sql"UPDATE scheduled_video SET status = $to WHERE video_metadata_id = $id AND status = $from".update.run
          .map(_ == 1)
      }
      .flatMap(findByIds)

  private def findByIds(ids: List[String]): ConnectionIO[Seq[ScheduledVideoDownload]] =
    NonEmptyList
      .fromList(ids)
      .fold[ConnectionIO[Seq[ScheduledVideoDownload]]](Applicative[ConnectionIO].pure(Seq.empty)) { nonEmptyIds =>
        (SelectQuery ++ fr"WHERE" ++ in(fr"scheduled_video.video_metadata_id", nonEmptyIds))
          .query[ScheduledVideoDownload]
          .to[Seq]
      }

  override def updateDownloadProgress(
    id: String,
    downloadedBytes: Long,
    timestamp: Instant
  ): ConnectionIO[Option[ScheduledVideoDownload]] =
    sql"""
        UPDATE scheduled_video
          SET downloaded_bytes = $downloadedBytes, last_updated_at = $timestamp
          WHERE video_metadata_id = $id AND last_updated_at < $timestamp
      """.update.run.singleUpdate
      .productR(OptionT(getById(id, None)))
      .value

  override def deleteById(id: String): ConnectionIO[Int] =
    for {
      _ <- sql"UPDATE scheduled_video SET error_id = NULL WHERE video_metadata_id = $id".update.run
      errorDeleteCount <- sql"DELETE FROM scheduled_video_error WHERE video_id = $id".update.run
      deleteCount <- sql"DELETE FROM scheduled_video WHERE video_metadata_id = $id".update.run
    } yield errorDeleteCount + deleteCount

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
  ): ConnectionIO[Seq[ScheduledVideoDownload]] =
    (SelectQuery ++ (if (maybeUserId.isEmpty) Fragment.empty
                     else fr"JOIN permission ON scheduled_video.video_metadata_id = permission.video_id")
      ++
        whereAndOpt(
          term.map(searchTerm => fr"video_metadata.title ILIKE ${"%" + searchTerm + "%"}"),
          videoUrls.map(urls => in(fr"video_metadata.url", urls)),
          schedulingStatuses.map(statuses => in(fr"scheduled_video.status", statuses)),
          durationRange.max.map(maxDuration => fr"video_metadata.duration <= $maxDuration"),
          durationRange.min.map(minDuration => fr"video_metadata.duration >= $minDuration"),
          sizeRange.max.map(maxSize => fr"video_metadata.size <= $maxSize"),
          sizeRange.min.map(minSize => fr"video_metadata.size >= $minSize"),
          videoSites.map(sites => in(fr"video_metadata.video_site", sites)),
          maybeUserId.map(userId => fr"permission.user_id = $userId")
        )
      ++ fr"ORDER BY"
      ++ schedulingSortByFieldName(sortBy)
      ++ ordering(order)
      // A tiebreaker on the unique video_metadata_id keeps paging (LIMIT/OFFSET) stable when the sort column
      // has duplicate values across rows; without it, rows can be skipped or duplicated across pages.
      ++ fr", scheduled_video.video_metadata_id ASC"
      ++ fr"LIMIT $pageSize OFFSET ${pageNumber * pageSize}")
      .query[ScheduledVideoDownload]
      .to[Seq]

  override def retryErroredScheduledDownloads(
    maybeUserId: Option[String],
    timestamp: Instant
  ): ConnectionIO[Seq[ScheduledVideoDownload]] =
    retryErroredScheduledDownloads(maybeUserId, timestamp, RetryPageSize)

  /**
    * Retries errored downloads one page at a time. Every retried row leaves the `Error` status, so the next
    * batch is always found on the first page again. Requesting subsequent page numbers would skip rows.
    */
  private def retryErroredScheduledDownloads(
    maybeUserId: Option[String],
    timestamp: Instant,
    pageSize: Int
  ): ConnectionIO[Seq[ScheduledVideoDownload]] =
    search(
      term = None,
      videoUrls = None,
      durationRange = RangeValue.all,
      sizeRange = RangeValue.all,
      pageNumber = 0,
      pageSize = pageSize,
      sortBy = SortBy.Date,
      order = Order.Descending,
      schedulingStatuses = Some(NonEmptyList.of(SchedulingStatus.Error)),
      videoSites = None,
      maybeUserId
    )
      .map(failed => NonEmptyList.fromList(failed.toList))
      .flatMap {
        case None => Applicative[ConnectionIO].pure(Seq.empty)

        case Some(failedScheduledVideos) =>
          val videoMetadataIds = failedScheduledVideos.map(_.videoMetadata.id)

          val retried =
            failedScheduledVideos.toList.map {
              _.copy(
                status = SchedulingStatus.Queued,
                lastUpdatedAt = timestamp,
                completedAt = None,
                errorInfo = None,
                retryCount = 0,
                retryAfter = None
              )
            }

          (fr"""
              UPDATE scheduled_video
              SET
                status = ${SchedulingStatus.Queued},
                error_id = NULL,
                completed_at = NULL,
                last_updated_at = $timestamp,
                retry_count = 0,
                retry_after = NULL
              WHERE
            """ ++ in(fr"video_metadata_id", videoMetadataIds)).update.run
            .productR {
              (fr"DELETE FROM scheduled_video_error WHERE" ++ in(fr"video_id", videoMetadataIds)).update.run
            }
            .productR {
              if (failedScheduledVideos.size < pageSize) Applicative[ConnectionIO].pure(retried)
              else retryErroredScheduledDownloads(maybeUserId, timestamp, pageSize).map(retried ++ _)
            }
      }

  override def staleTask(delay: FiniteDuration, timestamp: Instant): ConnectionIO[Option[ScheduledVideoDownload]] =
    sql"""
        SELECT video_metadata_id FROM scheduled_video
            WHERE
              status = ${SchedulingStatus.Stale}
              AND last_updated_at < ${timestamp.minusMillis(delay.toMillis)}
            LIMIT 1
    """
      .query[String]
      .option
      .flatMap {
        case Some(videoMetadataId) =>
          sql"""
              UPDATE scheduled_video
                  SET
                    status = ${SchedulingStatus.Acquired},
                    last_updated_at = $timestamp,
                    error_id = NULL
                  WHERE video_metadata_id = $videoMetadataId AND status = ${SchedulingStatus.Stale}
            """.update.run.singleUpdate
            .productR(OptionT(getById(videoMetadataId, None)))
            .value

        case None => Applicative[ConnectionIO].pure(None)
      }

  override def updateTimedOutTasks(
    timeout: FiniteDuration,
    timestamp: Instant
  ): ConnectionIO[Seq[ScheduledVideoDownload]] =
    (fr"SELECT video_metadata_id FROM scheduled_video WHERE" ++ timedOutCondition(timeout, timestamp) ++
      fr"ORDER BY scheduled_at ASC")
      .query[String]
      .to[List]
      .flatMap(ids => markTimedOutTasksAsStale(ids, timeout, timestamp))

  private def timedOutCondition(timeout: FiniteDuration, timestamp: Instant): Fragment =
    fr"""
      completed_at IS NULL
        AND status IN (${SchedulingStatus.Active}, ${SchedulingStatus.Acquired})
        AND last_updated_at < ${timestamp.minusMillis(timeout.toMillis)}
    """

  // The timed out condition is checked again, as a download can complete or make progress between the SELECT and the
  // UPDATE. Updating the rows one by one tells which of them actually changed, so only those are returned.
  private[daos] def markTimedOutTasksAsStale(
    ids: List[String],
    timeout: FiniteDuration,
    timestamp: Instant
  ): ConnectionIO[Seq[ScheduledVideoDownload]] =
    ids
      .filterA { id =>
        (fr"""
            UPDATE scheduled_video
              SET
                status = ${SchedulingStatus.Stale},
                last_updated_at = $timestamp,
                error_id = NULL
              WHERE video_metadata_id = $id AND""" ++ timedOutCondition(timeout, timestamp)).update.run.map(_ == 1)
      }
      .flatMap(findByIds)

  override def acquireTask(timestamp: Instant): ConnectionIO[Option[ScheduledVideoDownload]] =
    sql"""
      SELECT video_metadata_id FROM scheduled_video
        WHERE status = ${SchedulingStatus.Queued}
          AND (retry_after IS NULL OR retry_after <= $timestamp)
        ORDER BY scheduled_at ASC
        LIMIT 1
   """.query[String]
      .option
      .flatMap {
        _.fold[ConnectionIO[Option[ScheduledVideoDownload]]](
          Applicative[ConnectionIO].pure[Option[ScheduledVideoDownload]](None)
        ) { videoMetadataId =>
          sql"""
              UPDATE scheduled_video
                SET
                  status = ${SchedulingStatus.Acquired},
                  last_updated_at = $timestamp,
                  error_id = NULL
                WHERE video_metadata_id = $videoMetadataId AND status = ${SchedulingStatus.Queued}
            """.update.run.singleUpdate
            .productR(OptionT(getById(videoMetadataId, None)))
            .value
        }
      }

  override def setErrorById(
    id: String,
    throwable: Throwable,
    timestamp: Instant
  ): ConnectionIO[Option[ScheduledVideoDownload]] =
    insertError(id, throwable, timestamp)
      .flatMap { errorId =>
        sql"""
          UPDATE scheduled_video
            SET status = ${SchedulingStatus.Error}, last_updated_at = $timestamp, error_id = $errorId
            WHERE video_metadata_id = $id
         """.update.run.one
      }
      .productR(getById(id, None))

  override def scheduleRetry(
    id: String,
    throwable: Throwable,
    retryAfter: Instant,
    timestamp: Instant
  ): ConnectionIO[Option[ScheduledVideoDownload]] =
    insertError(id, throwable, timestamp)
      .flatMap { errorId =>
        sql"""
          UPDATE scheduled_video
            SET
              status = ${SchedulingStatus.Queued},
              last_updated_at = $timestamp,
              error_id = $errorId,
              retry_count = retry_count + 1,
              retry_after = $retryAfter
            WHERE video_metadata_id = $id
         """.update.run.one
      }
      .productR(getById(id, None))

  private def insertError(id: String, throwable: Throwable, timestamp: Instant): ConnectionIO[String] = {
    val errorId = s"$id-${timestamp.toEpochMilli}"

    sql"""
         INSERT INTO scheduled_video_error (id, created_at, video_id, error_message, error_details)
            VALUES (
              $errorId,
              $timestamp,
              $id,
              ${rootException(throwable)},
              ${throwable.getStackTrace.map(_.toString).mkString("\n")}
            )
     """.update.run.one.as(errorId)
  }

  private def rootException(throwable: Throwable): String =
    Option(throwable.getCause).fold(throwable.getMessage) { cause =>
      rootException(cause)
    }

  private val schedulingSortByFieldName: SortBy => Fragment =
    sortByFieldName.orElse {
      case SortBy.Date => fr"scheduled_video.scheduled_at"
      case _ => fr"RANDOM()"
    }
}
