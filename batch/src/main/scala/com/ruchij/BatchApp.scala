package com.ruchij

import cats.effect.{Blocker, ConcurrentEffect, ContextShift, ExitCode, IO, IOApp, Resource, Timer}
import com.ruchij.config.BatchServiceConfiguration
import com.ruchij.daos.doobie.DoobieTransactor
import com.ruchij.daos.scheduling.DoobieSchedulingDao
import com.ruchij.daos.video.DoobieVideoDao
import com.ruchij.daos.videometadata.DoobieVideoMetadataDao
import com.ruchij.migration.MigrationApp
import com.ruchij.services.download.Http4sDownloadService
import com.ruchij.services.scheduler.{Scheduler, SchedulerImpl}
import com.ruchij.services.scheduling.SchedulingServiceImpl
import com.ruchij.services.video.{VideoAnalysisServiceImpl, VideoServiceImpl}
import com.ruchij.services.worker.WorkExecutorImpl
import com.ruchij.types.FunctionKTypes.eitherToF
import org.http4s.client.blaze.BlazeClientBuilder
import pureconfig.ConfigSource

import scala.concurrent.ExecutionContext

object BatchApp extends IOApp {
  override def run(args: List[String]): IO[ExitCode] =
    for {
      configObjectSource <- IO.delay(ConfigSource.defaultApplication)
      batchServiceConfiguration <- BatchServiceConfiguration.parse[IO](configObjectSource)

      _ <- program[IO](batchServiceConfiguration, ExecutionContext.global).use(_.run)
    } yield ExitCode.Success

  def program[F[_]: ConcurrentEffect: ContextShift: Timer](
    batchServiceConfiguration: BatchServiceConfiguration,
    executionContext: ExecutionContext,
  ): Resource[F, Scheduler[F]] =
    for {
      client <- BlazeClientBuilder[F](executionContext).resource
      blocker <- Blocker[F]
      transactor <- Resource.liftF(DoobieTransactor.create[F](batchServiceConfiguration.databaseConfiguration, blocker))

      _ <- Resource.liftF(MigrationApp.migration[F](batchServiceConfiguration.databaseConfiguration, blocker))

      videoMetadataDao = new DoobieVideoMetadataDao[F](transactor)
      schedulingDao = new DoobieSchedulingDao[F](videoMetadataDao, transactor)
      videoDao = new DoobieVideoDao[F](transactor)

      videoAnalysisService = new VideoAnalysisServiceImpl[F](client)
      schedulingService = new SchedulingServiceImpl[F](videoAnalysisService, schedulingDao)
      downloadService = new Http4sDownloadService[F](client, blocker)
      videoService = new VideoServiceImpl[F](videoDao)

      workExecutor = new WorkExecutorImpl[F](
        schedulingService,
        videoAnalysisService,
        videoService,
        downloadService,
        batchServiceConfiguration.downloadConfiguration
      )

      scheduler = new SchedulerImpl(schedulingService, workExecutor, batchServiceConfiguration.workerConfiguration)
    } yield scheduler
}
