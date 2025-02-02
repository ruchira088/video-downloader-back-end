package com.ruchij.core.daos.scheduling

import cats.{Applicative, ApplicativeError}
import cats.data.{NonEmptyList, OptionT}
import cats.implicits._
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
import org.joda.time.DateTime

import scala.concurrent.duration.FiniteDuration

object DoobieSchedulingDao extends SchedulingDao[ConnectionIO] {

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
        scheduled_video.completed_at, scheduled_video_error.error_message, scheduled_video_error.error_details
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
    timestamp: DateTime
  ): ConnectionIO[Option[ScheduledVideoDownload]] =
    sql"""
        UPDATE scheduled_video
          SET
            completed_at = $timestamp,
            status = ${SchedulingStatus.Completed},
            last_updated_at = $timestamp,
            error_id = NULL
          WHERE
            completed_at IS NULL AND
            video_metadata_id = $id
      """.update.run.singleUpdate
      .productR(OptionT(getById(id, None)))
      .value

  override def updateSchedulingStatusById(
    id: String,
    status: SchedulingStatus,
    timestamp: DateTime
  ): ConnectionIO[Option[ScheduledVideoDownload]] =
    sql"""
        UPDATE scheduled_video
          SET
            status = $status,
            last_updated_at = $timestamp,
            error_id = NULL
          WHERE video_metadata_id = $id
       """.update.run.singleUpdate
      .productR(OptionT(getById(id, None)))
      .value

  override def updateSchedulingStatus(
    from: SchedulingStatus,
    to: SchedulingStatus
  ): ConnectionIO[Seq[ScheduledVideoDownload]] =
    sql"SELECT video_metadata_id FROM scheduled_video WHERE status = $from"
      .query[String]
      .to[Seq]
      .flatMap { ids =>
        ids.headOption.fold[ConnectionIO[Seq[ScheduledVideoDownload]]](Applicative[ConnectionIO].pure(Seq.empty)) {
          head =>
            val nonEmptyListIds = NonEmptyList(head, ids.tail.toList)

            (fr"UPDATE scheduled_video SET status = $to" ++ fr"WHERE" ++ in(fr"video_metadata_id", nonEmptyListIds)).update.run
              .productR {
                (SelectQuery ++ fr"WHERE" ++ in(fr"scheduled_video.video_metadata_id", nonEmptyListIds))
                  .query[ScheduledVideoDownload]
                  .to[Seq]
              }
        }
      }

  override def updateDownloadProgress(
    id: String,
    downloadedBytes: Long,
    timestamp: DateTime
  ): ConnectionIO[Option[ScheduledVideoDownload]] =
    sql"""
        UPDATE scheduled_video
          SET downloaded_bytes = $downloadedBytes, last_updated_at = $timestamp
          WHERE video_metadata_id = $id AND downloaded_bytes < $downloadedBytes
      """.update.run.singleUpdate.value
      .productR(getById(id, None))

  override def deleteById(id: String): ConnectionIO[Int] =
    sql"DELETE FROM scheduled_video WHERE video_metadata_id = $id".update.run

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
      ++ schedulingSortByFiledName(sortBy)
      ++ ordering(order)
      ++ fr"LIMIT $pageSize OFFSET ${pageNumber * pageSize}")
      .query[ScheduledVideoDownload]
      .to[Seq]

  override def staleTask(timestamp: DateTime): ConnectionIO[Option[ScheduledVideoDownload]] =
    sql"""
        SELECT video_metadata_id FROM scheduled_video
            WHERE status = ${SchedulingStatus.Stale}
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
    timestamp: DateTime
  ): ConnectionIO[Seq[ScheduledVideoDownload]] =
    sql"""
        SELECT video_metadata_id FROM scheduled_video
          WHERE completed_at IS NULL
            AND status IN (${SchedulingStatus.Active}, ${SchedulingStatus.Acquired})
            AND last_updated_at < ${timestamp.minus(timeout.toMillis)}
            ORDER BY scheduled_at ASC
      """
      .query[String]
      .to[Seq]
      .flatMap {
        _.traverse { videoMetadataId =>
          sql"""
              UPDATE scheduled_video
                SET
                  status = ${SchedulingStatus.Stale},
                  last_updated_at = $timestamp,
                  error_id = NULL
                WHERE
                  video_metadata_id = $videoMetadataId
            """.update.run
            .map(videoMetadataId -> _)
        }
      }
      .flatMap {
        _.collect { case (videoMetadataId, 1) => videoMetadataId }
          .traverse(getById(_, None))
          .map(_.flatten)
      }

  override def acquireTask(timestamp: DateTime): ConnectionIO[Option[ScheduledVideoDownload]] =
    sql"""
      SELECT video_metadata_id FROM scheduled_video
        WHERE status = ${SchedulingStatus.Queued}
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
    timestamp: DateTime
  ): ConnectionIO[Option[ScheduledVideoDownload]] = {
    val errorId = s"$id-${timestamp.getMillis}"

    sql"""
         INSERT INTO scheduled_video_error (id, created_at, video_id, error_message, error_details)
            VALUES (
              $errorId,
              $timestamp,
              $id,
              ${allExceptionMessages(throwable).mkString("\n")},
              ${throwable.getStackTrace.map(_.toString).mkString("\n")}
            )
     """.update.run.one
      .productR {
        sql"""
        UPDATE scheduled_video
          SET status = ${SchedulingStatus.Error}, last_updated_at = $timestamp, error_id = $errorId
          WHERE video_metadata_id = $id
       """.update.run.one
      }
      .productR(getById(id, None))
  }

  private def allExceptionMessages(throwable: Throwable): List[String] =
    if (throwable == null) List.empty else throwable.getMessage :: allExceptionMessages(throwable.getCause)

  private val schedulingSortByFiledName: SortBy => Fragment =
    sortByFieldName.orElse {
      case SortBy.Date => fr"scheduled_video.scheduled_at"
      case _ => fr"RANDOM()"
    }
}
