package com.ruchij.core.daos.scheduling

import cats.Applicative
import cats.data.{NonEmptyList, OptionT}
import cats.implicits._
import com.ruchij.core.daos.doobie.DoobieCustomMappings._
import com.ruchij.core.daos.doobie.DoobieUtils.{ordering, singleUpdate, sortByFieldName}
import com.ruchij.core.daos.scheduling.models.{ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.services.models.{Order, SortBy}
import doobie.free.connection.ConnectionIO
import doobie.implicits._
import doobie.util.fragment.Fragment
import doobie.util.fragments.{in, whereAndOpt}
import org.http4s.Uri
import org.joda.time.DateTime

object DoobieSchedulingDao extends SchedulingDao[ConnectionIO] {

  override def insert(scheduledVideoDownload: ScheduledVideoDownload): ConnectionIO[Int] =
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

  val SelectQuery: Fragment =
    fr"""
      SELECT
        scheduled_video.scheduled_at, scheduled_video.last_updated_at, scheduled_video.status,
        scheduled_video.downloaded_bytes, video_metadata.url, video_metadata.id, video_metadata.video_site,
        video_metadata.title, video_metadata.duration,video_metadata.size, file_resource.id,
        file_resource.created_at, file_resource.path, file_resource.media_type, file_resource.size,
        scheduled_video.completed_at
      FROM scheduled_video
      JOIN video_metadata ON scheduled_video.video_metadata_id = video_metadata.id
      JOIN file_resource ON video_metadata.thumbnail_id = file_resource.id
    """

  override def getById(id: String): ConnectionIO[Option[ScheduledVideoDownload]] =
    (SelectQuery ++ fr"WHERE scheduled_video.video_metadata_id = $id").query[ScheduledVideoDownload].option

  override def completeTask(id: String, timestamp: DateTime): ConnectionIO[Option[ScheduledVideoDownload]] =
    singleUpdate[ConnectionIO] {
      sql"""
        UPDATE scheduled_video
          SET completed_at = $timestamp, status = ${SchedulingStatus.Completed}, last_updated_at = $timestamp
          WHERE
            completed_at IS NULL AND
            video_metadata_id = $id
      """.update.run
    }.productR(OptionT(getById(id)))
      .value

  override def updateStatus(
    id: String,
    status: SchedulingStatus,
    timestamp: DateTime
  ): ConnectionIO[Option[ScheduledVideoDownload]] =
    singleUpdate[ConnectionIO] {
      sql"""
        UPDATE scheduled_video
          SET status = $status, last_updated_at = $timestamp
          WHERE video_metadata_id = $id
       """
        .update.run
    }.productR(OptionT(getById(id)))
      .value

  override def updatedDownloadProgress(
    id: String,
    downloadedBytes: Long,
    timestamp: DateTime
  ): ConnectionIO[Option[ScheduledVideoDownload]] =
    singleUpdate[ConnectionIO] {
      sql"""
        UPDATE scheduled_video
          SET downloaded_bytes = $downloadedBytes, last_updated_at = $timestamp
          WHERE video_metadata_id = $id
      """
        .update.run
    }.productR(OptionT(getById(id)))
      .value

  override def deleteById(id: String): ConnectionIO[Option[ScheduledVideoDownload]] =
    OptionT(getById(id))
      .productL {
        singleUpdate[ConnectionIO] {
          sql"DELETE FROM scheduled_video WHERE video_metadata_id = $id".update.run
        }
      }
      .value

  override def search(
    term: Option[String],
    videoUrls: Option[NonEmptyList[Uri]],
    pageNumber: Int,
    pageSize: Int,
    sortBy: SortBy,
    order: Order,
    schedulingStatus: Option[SchedulingStatus]
  ): ConnectionIO[Seq[ScheduledVideoDownload]] =
    (SelectQuery
      ++
        whereAndOpt(
          term.map(searchTerm => fr"video_metadata.title LIKE ${"%" + searchTerm + "%"}"),
          videoUrls.map(urls => in(fr"video_metadata.url", urls)),
          schedulingStatus.map(status => fr"scheduled_video.status = $status")
        )
      ++ fr"ORDER BY"
      ++ schedulingSortByFiledName(sortBy)
      ++ ordering(order)
      ++ fr"LIMIT $pageSize OFFSET ${pageNumber * pageSize}")
      .query[ScheduledVideoDownload]
      .to[Seq]

  override def staleTasks(timestamp: DateTime): ConnectionIO[Seq[ScheduledVideoDownload]] =
      sql"""
        SELECT video_metadata_id, last_updated_at FROM scheduled_video
          WHERE completed_at IS NULL
            AND status IN (${SchedulingStatus.Active}, ${SchedulingStatus.Stale}, ${SchedulingStatus.Acquired})
            AND last_updated_at < ${timestamp.minusMinutes(10)}
            ORDER BY scheduled_at ASC
      """
      .query[(String, DateTime)]
      .to[Seq]
      .flatMap {
        _.traverse {
          case (videoMetadataId, lastUpdatedAt) =>
            sql"""
              UPDATE scheduled_video
                SET status = ${SchedulingStatus.Stale}, last_updated_at = $timestamp
                WHERE
                  video_metadata_id = $videoMetadataId AND last_updated_at = $lastUpdatedAt
            """
              .update
              .run
              .map(videoMetadataId -> _)
        }
      }
      .flatMap {
        _.collect { case (videoMetadataId, 1) => videoMetadataId }
          .traverse(getById)
          .map(_.flatten)
      }

  override def acquireTask(timestamp: DateTime): ConnectionIO[Option[ScheduledVideoDownload]] =
    sql"""
      SELECT video_metadata_id FROM scheduled_video
        WHERE status = ${SchedulingStatus.Queued}
        ORDER BY scheduled_at ASC
        LIMIT 1
   """
      .query[String]
      .option
      .flatMap {
        _.fold[ConnectionIO[Option[ScheduledVideoDownload]]](Applicative[ConnectionIO].pure[Option[ScheduledVideoDownload]](None)) { videoMetadataId =>
          singleUpdate {
            sql"""
              UPDATE scheduled_video
                SET status = ${SchedulingStatus.Acquired}, last_updated_at = $timestamp
                WHERE video_metadata_id = $videoMetadataId AND status = ${SchedulingStatus.Queued}
            """
              .update
              .run
          }
            .productR(OptionT(getById(videoMetadataId)))
            .value
        }
      }

  val schedulingSortByFiledName: SortBy => Fragment =
    sortByFieldName.orElse {
      case SortBy.Date => fr"scheduled_video.scheduled_at"
    }

}
