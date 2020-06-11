package com.ruchij

import java.nio.file.Path
import java.util.concurrent.Executors

import cats.effect.{Blocker, ConcurrentEffect, ContextShift, ExitCode, IO, IOApp, Resource, Sync, Timer}
import com.ruchij.config.BatchServiceConfiguration
import com.ruchij.daos.doobie.DoobieTransactor
import com.ruchij.daos.resource.DoobieFileResourceDao
import com.ruchij.daos.scheduling.DoobieSchedulingDao
import com.ruchij.daos.snapshot.DoobieSnapshotDao
import com.ruchij.daos.video.DoobieVideoDao
import com.ruchij.daos.videometadata.DoobieVideoMetadataDao
import com.ruchij.daos.workers.DoobieWorkerDao
import com.ruchij.migration.MigrationApp
import com.ruchij.services.download.Http4sDownloadService
import com.ruchij.services.enrichment.VideoEnrichmentServiceImpl
import com.ruchij.services.hashing.MurmurHash3Service
import com.ruchij.services.repository.FileRepositoryService
import com.ruchij.services.scheduler.{Scheduler, SchedulerImpl}
import com.ruchij.services.scheduling.SchedulingServiceImpl
import com.ruchij.services.video.{VideoAnalysisServiceImpl, VideoServiceImpl}
import com.ruchij.services.worker.WorkExecutorImpl
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.client.middleware.FollowRedirect
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
    nonBlockingExecutionContext: ExecutionContext,
  ): Resource[F, Scheduler[F]] =
    for {
      baseClient <- BlazeClientBuilder[F](nonBlockingExecutionContext).resource
      httpClient = FollowRedirect(maxRedirects = 10)(baseClient)

      ioThreadPool <- Resource.liftF(Sync[F].delay(Executors.newCachedThreadPool()))
      ioBlocker = Blocker.liftExecutionContext(ExecutionContext.fromExecutor(ioThreadPool))

      processorCount <- Resource.liftF(Sync[F].delay(Runtime.getRuntime.availableProcessors()))
      cpuBlockingThreadPool <- Resource.liftF(Sync[F].delay(Executors.newFixedThreadPool(processorCount)))
      cpuBlocker = Blocker.liftExecutionContext(ExecutionContext.fromExecutor(cpuBlockingThreadPool))

      _ <- Resource.liftF(MigrationApp.migration[F](batchServiceConfiguration.databaseConfiguration, ioBlocker))
      transactor <- Resource.liftF(DoobieTransactor.create[F](batchServiceConfiguration.databaseConfiguration))

      fileResourceDao = new DoobieFileResourceDao[F](transactor)
      videoMetadataDao = new DoobieVideoMetadataDao[F](fileResourceDao)
      schedulingDao = new DoobieSchedulingDao[F](videoMetadataDao, transactor)
      videoDao = new DoobieVideoDao[F](fileResourceDao, transactor)
      workerDao = new DoobieWorkerDao[F](schedulingDao, transactor)
      snapshotDao = new DoobieSnapshotDao[F](fileResourceDao, transactor)

      repositoryService = new FileRepositoryService[F](ioBlocker)
      downloadService = new Http4sDownloadService[F](httpClient, repositoryService)
      hashingService = new MurmurHash3Service[F](cpuBlocker)
      videoAnalysisService = new VideoAnalysisServiceImpl[F](httpClient)
      schedulingService = new SchedulingServiceImpl[F](
        videoAnalysisService,
        schedulingDao,
        hashingService,
        downloadService,
        batchServiceConfiguration.downloadConfiguration
      )
      videoService = new VideoServiceImpl[F](videoDao)
      videoEnrichmentService =
        new VideoEnrichmentServiceImpl[F, Path](
          repositoryService,
          snapshotDao,
          ioBlocker,
          batchServiceConfiguration.downloadConfiguration
        )

      workExecutor = new WorkExecutorImpl[F](
        schedulingService,
        videoAnalysisService,
        videoService,
        hashingService,
        downloadService,
        videoEnrichmentService,
        batchServiceConfiguration.downloadConfiguration
      )

      scheduler = new SchedulerImpl(schedulingService, workExecutor, workerDao, batchServiceConfiguration.workerConfiguration)
    } yield scheduler
}
