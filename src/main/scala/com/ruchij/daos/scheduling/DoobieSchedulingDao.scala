package com.ruchij.daos.scheduling

import cats.effect.Bracket
import cats.implicits._
import doobie.implicits._
import com.ruchij.daos.doobie.DoobieCustomMappings._
import com.ruchij.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.daos.video.DoobieVideoMetadataDao
import doobie.util.transactor.Transactor

class DoobieSchedulingDao[F[_]: Bracket[*[_], Throwable]](
  doobieVideoMetadataDao: DoobieVideoMetadataDao[F],
  transactor: Transactor.Aux[F, Unit]
) extends SchedulingDao[F] {

  override def insert(scheduledVideoDownload: ScheduledVideoDownload): F[Int] =
    doobieVideoMetadataDao
      .insert(scheduledVideoDownload.videoMetadata)
      .productR {
        sql"""
          INSERT INTO scheduled_video (scheduled_at, url)
            VALUES (${scheduledVideoDownload.scheduledAt}, ${scheduledVideoDownload.videoMetadata.url})
         """
          .update.run.transact(transactor)
      }
}
