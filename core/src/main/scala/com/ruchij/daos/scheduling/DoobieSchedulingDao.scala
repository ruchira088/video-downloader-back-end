package com.ruchij.daos.scheduling

import cats.ApplicativeError
import cats.data.OptionT
import cats.effect.Bracket
import cats.implicits._
import com.ruchij.daos.doobie.singleUpdate
import com.ruchij.daos.doobie.DoobieCustomMappings._
import com.ruchij.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.daos.videometadata.DoobieVideoMetadataDao
import doobie.implicits._
import doobie.util.fragment.Fragment
import doobie.util.fragments.whereAndOpt
import doobie.util.transactor.Transactor
import org.joda.time.DateTime

class DoobieSchedulingDao[F[_]: Bracket[*[_], Throwable]](
  doobieVideoMetadataDao: DoobieVideoMetadataDao[F],
  transactor: Transactor.Aux[F, Unit]
) extends SchedulingDao[F] {

  override def insert(scheduledVideoDownload: ScheduledVideoDownload): F[Int] =
    doobieVideoMetadataDao
      .insert(scheduledVideoDownload.videoMetadata)
      .product {
        sql"""
          INSERT INTO scheduled_video (scheduled_at, last_updated_at, in_progress, video_metadata_id, downloaded_bytes, completed_at)
            VALUES (
              ${scheduledVideoDownload.scheduledAt},
              ${scheduledVideoDownload.lastUpdatedAt},
              ${scheduledVideoDownload.inProgress},
              ${scheduledVideoDownload.videoMetadata.id},
              ${scheduledVideoDownload.downloadedBytes},
              ${scheduledVideoDownload.completedAt}
              )
         """
          .update.run
      }
    .map { case (metadataResult, scheduledVideoResult) => metadataResult + scheduledVideoResult }
    .transact(transactor)

  override def updateDownloadProgress(id: String, downloadedBytes: Long, timestamp: DateTime): F[Int] =
    sql"""
      UPDATE scheduled_video SET downloaded_bytes = $downloadedBytes, last_updated_at = $timestamp
        WHERE video_metadata_id = $id
    """
      .update.run.transact(transactor)

  val SELECT_QUERY: Fragment =
    sql"""
        SELECT
          scheduled_video.scheduled_at, scheduled_video.last_updated_at, scheduled_video.in_progress,
          video_metadata.url, video_metadata.id, video_metadata.video_site, video_metadata.title, video_metadata.duration,
          video_metadata.size, file_resource.id, file_resource.created_at, file_resource.path,
          file_resource.media_type, file_resource.size, scheduled_video.downloaded_bytes, scheduled_video.completed_at
        FROM scheduled_video
        JOIN video_metadata ON scheduled_video.video_metadata_id = video_metadata.id
        JOIN file_resource ON video_metadata.thumbnail_id = file_resource.id
      """

  override def getById(id: String): OptionT[F, ScheduledVideoDownload] =
    OptionT {
      (SELECT_QUERY ++ sql"WHERE scheduled_video.video_metadata_id = $id")
        .query[ScheduledVideoDownload].option.transact(transactor)
    }

  override def setInProgress(id: String, inProgress: Boolean): OptionT[F, ScheduledVideoDownload] =
    singleUpdate[F] {
      sql"UPDATE scheduled_video SET in_progress = $inProgress WHERE video_metadata_id = $id AND in_progress = ${!inProgress}"
        .update.run.transact(transactor)
    }
      .productR(getById(id))


  override def completeTask(id: String, timestamp: DateTime): OptionT[F, ScheduledVideoDownload] =
    singleUpdate[F] {
      sql"""
        UPDATE scheduled_video
          SET
            in_progress = false,
            completed_at = $timestamp,
            last_updated_at = $timestamp
          WHERE
            in_progress = true AND
            completed_at IS NULL AND
            video_metadata_id = $id
      """.update.run.transact(transactor)
    }
      .productR(getById(id))


  override def search(term: Option[String], pageNumber: Int, pageSize: Int): F[Seq[ScheduledVideoDownload]] =
    (SELECT_QUERY ++ whereAndOpt(term.map(searchTerm => sql"title LIKE ${"%" + searchTerm + "%"}"))
      ++ sql"OFFSET ${pageNumber * pageSize} LIMIT $pageSize")
    .query[ScheduledVideoDownload]
    .to[Seq]
    .transact(transactor)

  override val retrieveNewTask: OptionT[F, ScheduledVideoDownload] =
    OptionT {
      (SELECT_QUERY ++ sql"WHERE scheduled_video.in_progress = false AND scheduled_video.completed_at IS NULL LIMIT 1")
        .query[ScheduledVideoDownload]
        .to[Seq]
        .transact(transactor)
        .map(_.headOption)
    }
  override def active(after: DateTime, before: DateTime): F[Seq[ScheduledVideoDownload]] =
    if (after.isBefore(before))
      (SELECT_QUERY ++
        sql"""
          WHERE
            scheduled_video.last_updated_at >= $after
            AND scheduled_video.last_updated_at < $before
          """
        )
        .query[ScheduledVideoDownload]
        .to[Seq]
        .transact(transactor)
    else
      ApplicativeError[F, Throwable].raiseError(new IllegalArgumentException(s"$after is After $before"))
}
