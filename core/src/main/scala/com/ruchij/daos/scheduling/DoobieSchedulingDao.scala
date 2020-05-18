package com.ruchij.daos.scheduling

import cats.ApplicativeError
import cats.data.OptionT
import cats.effect.Bracket
import cats.implicits._
import com.ruchij.daos.doobie.singleUpdate
import com.ruchij.daos.doobie.DoobieCustomMappings._
import com.ruchij.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.daos.videometadata.DoobieVideoMetadataDao
import doobie.free.connection.ConnectionIO
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
          INSERT INTO scheduled_video (scheduled_at, last_updated_at, video_metadata_id, downloaded_bytes, completed_at)
            VALUES (
              ${scheduledVideoDownload.scheduledAt},
              ${scheduledVideoDownload.lastUpdatedAt},
              ${scheduledVideoDownload.videoMetadata.id},
              ${scheduledVideoDownload.downloadedBytes},
              ${scheduledVideoDownload.completedAt}
              )
         """.update.run
      }
      .map { case (metadataResult, scheduledVideoResult) => metadataResult + scheduledVideoResult }
      .transact(transactor)

  override def updateDownloadProgress(id: String, downloadedBytes: Long, timestamp: DateTime): F[Int] =
    sql"""
      UPDATE scheduled_video SET downloaded_bytes = $downloadedBytes, last_updated_at = $timestamp
        WHERE video_metadata_id = $id
    """.update.run.transact(transactor)

  val SELECT_QUERY: Fragment =
    sql"""
        SELECT
          scheduled_video.scheduled_at, scheduled_video.last_updated_at, scheduled_video.download_started_at,
          video_metadata.url, video_metadata.id, video_metadata.video_site, video_metadata.title, video_metadata.duration,
          video_metadata.size, file_resource.id, file_resource.created_at, file_resource.path,
          file_resource.media_type, file_resource.size, scheduled_video.downloaded_bytes, scheduled_video.completed_at
        FROM scheduled_video
        JOIN video_metadata ON scheduled_video.video_metadata_id = video_metadata.id
        JOIN file_resource ON video_metadata.thumbnail_id = file_resource.id
      """

  override def getById(id: String): OptionT[F, ScheduledVideoDownload] = findById(id).transact(transactor)

  override def completeTask(id: String, timestamp: DateTime): OptionT[F, ScheduledVideoDownload] =
    singleUpdate[ConnectionIO] {
      sql"""
        UPDATE scheduled_video
          SET completed_at = $timestamp, last_updated_at = $timestamp
          WHERE
            completed_at IS NULL AND
            video_metadata_id = $id
      """.update.run
    }
    .productR(findById(id))
    .transact(transactor)

  override def search(term: Option[String], pageNumber: Int, pageSize: Int): F[Seq[ScheduledVideoDownload]] =
    (SELECT_QUERY ++ whereAndOpt(term.map(searchTerm => sql"title LIKE ${"%" + searchTerm + "%"}"))
      ++ sql"LIMIT $pageSize OFFSET ${pageNumber * pageSize}")
      .query[ScheduledVideoDownload]
      .to[Seq]
      .transact(transactor)

  override def retrieveNewTask(timestamp: DateTime): OptionT[F, ScheduledVideoDownload] =
    OptionT[ConnectionIO, String] {
      sql"SELECT video_metadata_id FROM scheduled_video WHERE download_started_at IS NULL LIMIT 1"
        .query[String]
        .to[Seq]
        .map(_.headOption)
    }
      .flatTap { id =>
        singleUpdate {
          sql"UPDATE scheduled_video SET download_started_at = $timestamp WHERE video_metadata_id = $id".update.run
        }
      }
      .flatMap(findById)
      .transact(transactor)

  override def active(after: DateTime, before: DateTime): F[Seq[ScheduledVideoDownload]] =
    if (after.isBefore(before))
      (SELECT_QUERY ++ sql"WHERE scheduled_video.last_updated_at >= $after AND scheduled_video.last_updated_at < $before")
        .query[ScheduledVideoDownload]
        .to[Seq]
        .transact(transactor)
    else
      ApplicativeError[F, Throwable].raiseError(new IllegalArgumentException(s"$after is After $before"))

  def findById(id: String): OptionT[ConnectionIO, ScheduledVideoDownload] =
    OptionT {
      (SELECT_QUERY ++ sql"WHERE scheduled_video.video_metadata_id = $id").query[ScheduledVideoDownload]
        .option
    }
}
