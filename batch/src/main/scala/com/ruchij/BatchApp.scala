package com.ruchij

import cats.effect.{Blocker, Clock, ConcurrentEffect, ContextShift, ExitCode, IO, IOApp, Resource}
import com.ruchij.config.{DatabaseConfiguration, DownloadConfiguration}
import com.ruchij.daos.doobie.DoobieTransactor
import com.ruchij.daos.scheduling.DoobieSchedulingDao
import com.ruchij.daos.video.DoobieVideoDao
import com.ruchij.daos.videometadata.DoobieVideoMetadataDao
import com.ruchij.services.download.Http4sDownloadService
import com.ruchij.services.scheduling.SchedulingServiceImpl
import com.ruchij.services.video.{VideoAnalysisServiceImpl, VideoServiceImpl}
import com.ruchij.services.worker.{Worker, WorkerImpl}
import org.http4s.client.blaze.BlazeClientBuilder

import scala.concurrent.ExecutionContext

object BatchApp extends IOApp {
  override def run(args: List[String]): IO[ExitCode] =
    program[IO](ExecutionContext.global, DownloadConfiguration(), DatabaseConfiguration())
      .use {
        worker => worker.execute()
      }

  def program[F[_]: ConcurrentEffect: ContextShift: Clock](
    executionContext: ExecutionContext,
    downloadConfiguration: DownloadConfiguration,
    databaseConfiguration: DatabaseConfiguration
  ): Resource[F, Worker[F]] =
    for {
      client <- BlazeClientBuilder[F](executionContext).resource
      blocker <- Blocker[F]
      transactor <- Resource.liftF(DoobieTransactor.create[F](databaseConfiguration, blocker))

      videoMetadataDao = new DoobieVideoMetadataDao[F](transactor)
      schedulingDao = new DoobieSchedulingDao[F](videoMetadataDao, transactor)
      videoDao = new DoobieVideoDao[F](transactor)

      videoAnalysisService = new VideoAnalysisServiceImpl[F](client)
      schedulingService = new SchedulingServiceImpl[F](videoAnalysisService, schedulingDao)
      downloadService = new Http4sDownloadService[F](client, blocker)
      videoService = new VideoServiceImpl[F](videoDao)

      worker = new WorkerImpl[F](
        schedulingService,
        videoAnalysisService,
        videoService,
        downloadService,
        downloadConfiguration
      )
    } yield worker
}
