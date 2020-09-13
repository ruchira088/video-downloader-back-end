package com.ruchij

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
import com.ruchij.logging.Logger
import com.ruchij.migration.MigrationApp
import com.ruchij.services.download.Http4sDownloadService
import com.ruchij.services.enrichment.VideoEnrichmentServiceImpl
import com.ruchij.services.hashing.MurmurHash3Service
import com.ruchij.services.repository.{FileRepositoryService, PathFileTypeDetector}
import com.ruchij.services.scheduler.{Scheduler, SchedulerImpl}
import com.ruchij.services.scheduling.SchedulingServiceImpl
import com.ruchij.services.sync.SynchronizationServiceImpl
import com.ruchij.services.video.{VideoAnalysisServiceImpl, VideoServiceImpl}
import com.ruchij.services.worker.WorkExecutorImpl
import com.ruchij.types.FunctionKTypes
import doobie.free.connection.ConnectionIO
import org.apache.tika.Tika
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.client.middleware.FollowRedirect
import pureconfig.ConfigSource

import scala.concurrent.ExecutionContext

object BatchApp extends IOApp {

  private val logger = Logger[IO, BatchApp.type]

  override def run(args: List[String]): IO[ExitCode] =
    for {
      configObjectSource <- IO.delay(ConfigSource.defaultApplication)
      batchServiceConfiguration <- BatchServiceConfiguration.parse[IO](configObjectSource)

      _ <- program[IO](batchServiceConfiguration, ExecutionContext.global)
        .use { scheduler =>
          scheduler.init *>
            logger.infoF("Scheduler has started") *>
            scheduler.run
        }
    } yield ExitCode.Success

  def program[F[+ _]: ConcurrentEffect: ContextShift: Timer](
    batchServiceConfiguration: BatchServiceConfiguration,
    nonBlockingExecutionContext: ExecutionContext,
  ): Resource[F, Scheduler[F]] =
    Resource.liftF(DoobieTransactor.create[F](batchServiceConfiguration.databaseConfiguration))
    .map(FunctionKTypes.transaction[F])
    .flatMap { implicit transaction =>
      for {
        baseClient <- BlazeClientBuilder[F](nonBlockingExecutionContext).resource
        httpClient = FollowRedirect(maxRedirects = 10)(baseClient)

        ioThreadPool <- Resource.liftF(Sync[F].delay(Executors.newCachedThreadPool()))
        ioBlocker = Blocker.liftExecutionContext(ExecutionContext.fromExecutor(ioThreadPool))

        processorCount <- Resource.liftF(Sync[F].delay(Runtime.getRuntime.availableProcessors()))
        cpuBlockingThreadPool <- Resource.liftF(Sync[F].delay(Executors.newFixedThreadPool(processorCount)))
        cpuBlocker = Blocker.liftExecutionContext(ExecutionContext.fromExecutor(cpuBlockingThreadPool))

        _ <- Resource.liftF(MigrationApp.migration[F](batchServiceConfiguration.databaseConfiguration, ioBlocker))

        workerDao = new DoobieWorkerDao(DoobieSchedulingDao)

        repositoryService = new FileRepositoryService[F](ioBlocker)
        downloadService = new Http4sDownloadService[F](httpClient, repositoryService)
        hashingService = new MurmurHash3Service[F](cpuBlocker)
        videoAnalysisService = new VideoAnalysisServiceImpl[F](httpClient)
        schedulingService = new SchedulingServiceImpl[F, ConnectionIO](
          videoAnalysisService,
          DoobieSchedulingDao,
          DoobieVideoMetadataDao,
          DoobieFileResourceDao,
          hashingService,
          downloadService,
          batchServiceConfiguration.downloadConfiguration
        )

        fileTypeDetector = new PathFileTypeDetector[F](new Tika(), ioBlocker)

        videoService = new VideoServiceImpl[F, ConnectionIO](DoobieVideoDao, DoobieVideoMetadataDao, DoobieSnapshotDao, DoobieFileResourceDao)

        videoEnrichmentService = new VideoEnrichmentServiceImpl[F, repositoryService.BackedType, ConnectionIO](
          repositoryService,
          hashingService,
          DoobieSnapshotDao,
          DoobieFileResourceDao,
          ioBlocker,
          batchServiceConfiguration.downloadConfiguration
        )

        synchronizationService = new SynchronizationServiceImpl[F, repositoryService.BackedType, ConnectionIO](
          repositoryService,
          DoobieFileResourceDao,
          DoobieVideoMetadataDao,
          videoService,
          videoEnrichmentService,
          hashingService,
          fileTypeDetector,
          ioBlocker,
          batchServiceConfiguration.downloadConfiguration
        )

        workExecutor = new WorkExecutorImpl[F, ConnectionIO](
          DoobieFileResourceDao,
          schedulingService,
          videoAnalysisService,
          videoService,
          hashingService,
          downloadService,
          videoEnrichmentService,
          batchServiceConfiguration.downloadConfiguration
        )

        scheduler = new SchedulerImpl(
          schedulingService,
          synchronizationService,
          workExecutor,
          workerDao,
          batchServiceConfiguration.workerConfiguration
        )
      } yield scheduler
    }
}
