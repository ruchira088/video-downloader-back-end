package com.ruchij.services.scheduling

import java.util.concurrent.TimeUnit

import cats.Monad
import cats.effect.Clock
import cats.implicits._
import com.ruchij.daos.scheduling.SchedulingDao
import com.ruchij.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.services.video.VideoService
import org.http4s.Uri
import org.joda.time.DateTime

class SchedulingServiceImpl[F[_]: Monad: Clock](videoService: VideoService[F], schedulingDao: SchedulingDao[F])
    extends SchedulingService[F] {

  override def schedule(uri: Uri): F[ScheduledVideoDownload] =
    for {
      videoMetadata <- videoService.metadata(uri)
      timestamp <- Clock[F].realTime(TimeUnit.MILLISECONDS)

      scheduledVideoDownload = ScheduledVideoDownload(new DateTime(timestamp), videoMetadata)
      _ <- schedulingDao.insert(scheduledVideoDownload)
    } yield scheduledVideoDownload
}
