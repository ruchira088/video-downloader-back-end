package com.ruchij.daos.scheduling

import cats.ApplicativeError
import cats.data.OptionT
import cats.implicits._
import com.ruchij.daos.doobie.DoobieCustomMappings._
import com.ruchij.daos.doobie.DoobieUtils.{ordering, singleUpdate, sortByFieldName}
import com.ruchij.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.services.models.{Order, SortBy}
import doobie.free.connection.ConnectionIO
import doobie.implicits._
import doobie.util.fragment.Fragment
import doobie.util.fragments.whereAndOpt
import org.joda.time.DateTime

object DoobieSchedulingDao extends SchedulingDao[ConnectionIO] {

  override def insert(scheduledVideoDownload: ScheduledVideoDownload): ConnectionIO[Int] =
    sql"""
      INSERT INTO scheduled_video (scheduled_at, last_updated_at, video_metadata_id, downloaded_bytes, completed_at)
        VALUES (
          ${scheduledVideoDownload.scheduledAt},
          ${scheduledVideoDownload.lastUpdatedAt},
          ${scheduledVideoDownload.videoMetadata.id},
          ${scheduledVideoDownload.downloadedBytes},
          ${scheduledVideoDownload.completedAt}
          )
     """.update.run

  override def updateDownloadProgress(id: String, downloadedBytes: Long, timestamp: DateTime): ConnectionIO[Int] =
    sql"""
      UPDATE scheduled_video SET downloaded_bytes = $downloadedBytes, last_updated_at = $timestamp
        WHERE video_metadata_id = $id
    """.update.run

  val selectQuery: Fragment =
    fr"""
      SELECT
        scheduled_video.scheduled_at, scheduled_video.last_updated_at, scheduled_video.download_started_at,
        video_metadata.url, video_metadata.id, video_metadata.video_site, video_metadata.title, video_metadata.duration,
        video_metadata.size, file_resource.id, file_resource.created_at, file_resource.path,
        file_resource.media_type, file_resource.size, scheduled_video.downloaded_bytes, scheduled_video.completed_at
      FROM scheduled_video
      JOIN video_metadata ON scheduled_video.video_metadata_id = video_metadata.id
      JOIN file_resource ON video_metadata.thumbnail_id = file_resource.id
    """

  override def getById(id: String): ConnectionIO[Option[ScheduledVideoDownload]] = findById(id)

  override def completeTask(id: String, timestamp: DateTime): ConnectionIO[Option[ScheduledVideoDownload]] =
    singleUpdate[ConnectionIO] {
      sql"""
        UPDATE scheduled_video
          SET completed_at = $timestamp, last_updated_at = $timestamp
          WHERE
            completed_at IS NULL AND
            video_metadata_id = $id
      """.update.run
    }.productR(OptionT(findById(id))).value

  override def search(
    term: Option[String],
    pageNumber: Int,
    pageSize: Int,
    sortBy: SortBy,
    order: Order
  ): ConnectionIO[Seq[ScheduledVideoDownload]] =
    (selectQuery
      ++ fr"ORDER BY"
      ++ schedulingSortByFiledName(sortBy)
      ++ ordering(order)
      ++ whereAndOpt(term.map(searchTerm => fr"title LIKE ${"%" + searchTerm + "%"}"))
      ++ fr"LIMIT $pageSize OFFSET ${pageNumber * pageSize}")
      .query[ScheduledVideoDownload]
      .to[Seq]

  override def retrieveNewTask(timestamp: DateTime): ConnectionIO[Option[ScheduledVideoDownload]] =
    OptionT[ConnectionIO, String] {
      sql"SELECT video_metadata_id FROM scheduled_video WHERE download_started_at IS NULL LIMIT 1"
        .query[String]
        .to[Seq]
        .map(_.headOption)
    }.flatTap { id =>
        singleUpdate {
          sql"UPDATE scheduled_video SET download_started_at = $timestamp WHERE video_metadata_id = $id".update.run
        }
      }
      .flatMapF(findById)
      .value

  override def retrieveStaledTask(timestamp: DateTime): ConnectionIO[Option[ScheduledVideoDownload]] =
    OptionT {
      sql"""
        SELECT video_metadata_id FROM scheduled_video
          WHERE
            last_updated_at < ${timestamp.minusSeconds(30)} AND
            download_started_at IS NOT NULL AND
            completed_at IS NULL
          LIMIT 1
      """
        .query[String]
        .option
    }.flatTap { id =>
        singleUpdate {
          sql"UPDATE scheduled_video SET last_updated_at = $timestamp WHERE video_metadata_id = $id".update.run
        }
      }
      .flatMapF(findById)
      .value

  override def active(after: DateTime, before: DateTime): ConnectionIO[Seq[ScheduledVideoDownload]] =
    if (after.isBefore(before))
      (selectQuery ++ fr"WHERE scheduled_video.last_updated_at >= $after AND scheduled_video.last_updated_at < $before")
        .query[ScheduledVideoDownload]
        .to[Seq]
    else
      ApplicativeError[ConnectionIO, Throwable].raiseError(new IllegalArgumentException(s"$after is After $before"))

  def findById(id: String): ConnectionIO[Option[ScheduledVideoDownload]] =
    (selectQuery ++ fr"WHERE scheduled_video.video_metadata_id = $id").query[ScheduledVideoDownload].option

  val schedulingSortByFiledName: SortBy => Fragment =
    sortByFieldName.orElse {
      case SortBy.Date => fr"scheduled_video.scheduled_at"
    }

}
