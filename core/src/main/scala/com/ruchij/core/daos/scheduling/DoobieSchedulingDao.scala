package com.ruchij.core.daos.scheduling

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
      INSERT INTO scheduled_video (scheduled_at, last_updated_at, status, video_metadata_id, completed_at)
        VALUES (
          ${scheduledVideoDownload.scheduledAt},
          ${scheduledVideoDownload.lastUpdatedAt},
          ${scheduledVideoDownload.status},
          ${scheduledVideoDownload.videoMetadata.id},
          ${scheduledVideoDownload.completedAt}
          )
     """.update.run

  val SelectQuery: Fragment =
    fr"""
      SELECT
        scheduled_video.scheduled_at, scheduled_video.last_updated_at, scheduled_video.status,
        video_metadata.url, video_metadata.id,video_metadata.video_site, video_metadata.title,
        video_metadata.duration,video_metadata.size, file_resource.id, file_resource.created_at,
        file_resource.path, file_resource.media_type, file_resource.size, scheduled_video.completed_at
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
    }
      .productR(OptionT(getById(id))).value

  override def updatedBetween(start: DateTime, end: DateTime): ConnectionIO[Seq[ScheduledVideoDownload]] =
    (SelectQuery ++ fr"WHERE scheduled_video.last_updated_at >= $start AND scheduled_video.last_updated_at < $end")
      .query[ScheduledVideoDownload]
      .to[Seq]

  override def updateStatus(id: String, status: SchedulingStatus, timestamp: DateTime): ConnectionIO[Option[ScheduledVideoDownload]] =
    singleUpdate[ConnectionIO] {
      sql"""
        UPDATE scheduled_video
          SET status = $status, last_updated_at = $timestamp
          WHERE video_metadata_id = $id
       """.update.run
    }
      .productR(OptionT(getById(id))).value

  override def search(
    term: Option[String],
    videoUrls: Option[NonEmptyList[Uri]],
    pageNumber: Int,
    pageSize: Int,
    sortBy: SortBy,
    order: Order
  ): ConnectionIO[Seq[ScheduledVideoDownload]] =
    (SelectQuery
      ++
        whereAndOpt(
          term.map(searchTerm => fr"title LIKE ${"%" + searchTerm + "%"}"),
          videoUrls.map(urls => in(fr"video_metadata.url", urls))
        )
      ++ fr"ORDER BY"
      ++ schedulingSortByFiledName(sortBy)
      ++ ordering(order)
      ++ fr"LIMIT $pageSize OFFSET ${pageNumber * pageSize}")
      .query[ScheduledVideoDownload]
      .to[Seq]

  def getByStatus(status: SchedulingStatus): OptionT[ConnectionIO, String] =
    OptionT {
      sql"""
        SELECT scheduled_video.video_metadata_id FROM scheduled_video
          WHERE status = $status
          ORDER BY scheduled_video.scheduled_at
          LIMIT 1
      """
        .query[String]
        .option
    }

  override val retrieveTask: ConnectionIO[Option[ScheduledVideoDownload]] =
    getByStatus(SchedulingStatus.Queued)
      .orElse(getByStatus(SchedulingStatus.Error))
      .flatMapF(getById)
      .value

  val schedulingSortByFiledName: SortBy => Fragment =
    sortByFieldName.orElse {
      case SortBy.Date => fr"scheduled_video.scheduled_at"
    }

}
