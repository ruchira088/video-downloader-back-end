package com.ruchij.batch

import java.util.concurrent.Executors

import cats.effect.{Blocker, ConcurrentEffect, ContextShift, ExitCode, IO, IOApp, Resource, Sync, Timer}
import com.ruchij.batch.config.BatchServiceConfiguration
import com.ruchij.batch.services.enrichment.VideoEnrichmentServiceImpl
import com.ruchij.batch.services.scheduler.{Scheduler, SchedulerImpl}
import com.ruchij.batch.services.sync.SynchronizationServiceImpl
import com.ruchij.batch.services.worker.WorkExecutorImpl
import com.ruchij.core.daos.doobie.DoobieTransactor
import com.ruchij.core.daos.resource.DoobieFileResourceDao
import com.ruchij.core.daos.scheduling.DoobieSchedulingDao
import com.ruchij.core.daos.snapshot.DoobieSnapshotDao
import com.ruchij.core.daos.video.DoobieVideoDao
import com.ruchij.core.daos.videometadata.DoobieVideoMetadataDao
import com.ruchij.core.daos.workers.DoobieWorkerDao
import com.ruchij.core.kv.{KeySpacedKeyValueStore, RedisKeyValueStore}
import com.ruchij.core.logging.Logger
import com.ruchij.core.services.download.Http4sDownloadService
import com.ruchij.core.services.hashing.MurmurHash3Service
import com.ruchij.core.services.repository.{FileRepositoryService, PathFileTypeDetector}
import com.ruchij.core.services.scheduling.SchedulingServiceImpl
import com.ruchij.core.services.scheduling.models.DownloadProgress
import com.ruchij.core.services.scheduling.models.DownloadProgress.{DownloadProgressKey, DownloadProgressKeySpace}
import com.ruchij.core.services.video.{VideoAnalysisServiceImpl, VideoServiceImpl}
import com.ruchij.core.types.FunctionKTypes
import com.ruchij.migration.MigrationApp
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.effect.Log.Stdout.instance
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
    Resource
      .liftF(DoobieTransactor.create[F](batchServiceConfiguration.databaseConfiguration))
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

          redisCommands <- Redis[F].utf8(batchServiceConfiguration.redisConfiguration.uri)
          keyValueStore = new RedisKeyValueStore[F](redisCommands, RedisKeyValueStore.Ttl)
          downloadProgressKeyStore = new KeySpacedKeyValueStore[F, DownloadProgressKey, DownloadProgress](DownloadProgressKeySpace, keyValueStore)

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
            downloadProgressKeyStore,
            hashingService,
            downloadService,
            batchServiceConfiguration.downloadConfiguration
          )

          fileTypeDetector = new PathFileTypeDetector[F](new Tika(), ioBlocker)

          videoService = new VideoServiceImpl[F, ConnectionIO](
            DoobieVideoDao,
            DoobieVideoMetadataDao,
            DoobieSnapshotDao,
            DoobieFileResourceDao
          )

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
