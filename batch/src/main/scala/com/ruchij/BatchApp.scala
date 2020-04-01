package com.ruchij

import java.nio.file.Paths

import cats.effect.{Blocker, ConcurrentEffect, ContextShift, ExitCode, IO, IOApp, Resource, Timer}
import cats.implicits._
import com.ruchij.config.{BatchConfiguration, DatabaseConfiguration, DownloadConfiguration}
import com.ruchij.daos.doobie.DoobieTransactor
import com.ruchij.daos.scheduling.DoobieSchedulingDao
import com.ruchij.daos.video.DoobieVideoDao
import com.ruchij.daos.videometadata.DoobieVideoMetadataDao
import com.ruchij.services.download.Http4sDownloadService
import com.ruchij.services.scheduler.{Scheduler, SchedulerImpl}
import com.ruchij.services.scheduling.SchedulingServiceImpl
import com.ruchij.services.video.{VideoAnalysisServiceImpl, VideoServiceImpl}
import com.ruchij.services.worker.WorkExecutorImpl
import org.http4s.client.blaze.BlazeClientBuilder

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.language.postfixOps

object BatchApp extends IOApp {
  override def run(args: List[String]): IO[ExitCode] =
    program[IO](
      DownloadConfiguration(
        Paths.get("/Users/ruchira/Development/video-downloader/temp"),
        Paths.get("/Users/ruchira/Development/video-downloader/temp")
      ),
      DatabaseConfiguration(
        "jdbc:postgresql://localhost:5432/video-downloader",
        "yocEgxiJYtmeivUv",
        "X82z9_TTN^|2C#MxkP0^L88&1685aiR="
      ),
      BatchConfiguration(5, 60 seconds),
      ExecutionContext.global
    ).use(_.run).as(ExitCode.Success)

  def program[F[_]: ConcurrentEffect: ContextShift: Timer](
    downloadConfiguration: DownloadConfiguration,
    databaseConfiguration: DatabaseConfiguration,
    batchConfiguration: BatchConfiguration,
    executionContext: ExecutionContext,
  ): Resource[F, Scheduler[F]] =
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

      workExecutor = new WorkExecutorImpl[F](
        schedulingService,
        videoAnalysisService,
        videoService,
        downloadService,
        downloadConfiguration
      )

      scheduler = new SchedulerImpl(workExecutor, schedulingService, batchConfiguration)
    } yield scheduler
}
