package com.ruchij.daos.scheduling

import cats.effect.Bracket
import cats.implicits._
import doobie.implicits._
import com.ruchij.daos.doobie.DoobieCustomMappings._
import com.ruchij.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.daos.videometadata.DoobieVideoMetadataDao
import doobie.util.transactor.Transactor
import org.http4s.Uri
import org.joda.time.DateTime

class DoobieSchedulingDao[F[_]: Bracket[*[_], Throwable]](
  doobieVideoMetadataDao: DoobieVideoMetadataDao[F],
  transactor: Transactor.Aux[F, Unit]
) extends SchedulingDao[F] {

  override def insert(scheduledVideoDownload: ScheduledVideoDownload): F[Int] =
    doobieVideoMetadataDao
      .insert(scheduledVideoDownload.videoMetadata)
      .productR {
        sql"""
          INSERT INTO scheduled_video (scheduled_at, last_updated_at, in_progress, url, downloaded_bytes, completed_at)
            VALUES (
              ${scheduledVideoDownload.scheduledAt},
              ${scheduledVideoDownload.lastUpdatedAt},
              ${scheduledVideoDownload.inProgress},
              ${scheduledVideoDownload.videoMetadata.url},
              ${scheduledVideoDownload.downloadedBytes},
              ${scheduledVideoDownload.completedAt}
              )
         """
          .update.run.transact(transactor)
      }

  override def updateDownloadProgress(url: Uri, downloadedBytes: Long, timestamp: DateTime): F[Int] =
    sql"""
      UPDATE scheduled_video SET downloaded_bytes = $downloadedBytes, last_updated_at = $timestamp
        where url = $url
    """
      .update.run.transact(transactor)
}
