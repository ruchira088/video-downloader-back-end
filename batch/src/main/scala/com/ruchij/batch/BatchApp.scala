package com.ruchij.batch

import cats.effect._
import cats.effect.kernel.Async
import cats.effect.std.Dispatcher
import com.ruchij.batch.config.BatchServiceConfiguration
import com.ruchij.batch.daos.filesync.DoobieFileSyncDao
import com.ruchij.batch.daos.workers.DoobieWorkerDao
import com.ruchij.batch.services.enrichment.VideoEnrichmentServiceImpl
import com.ruchij.batch.services.scheduler.{Scheduler, SchedulerImpl}
import com.ruchij.batch.services.scheduling.BatchSchedulingServiceImpl
import com.ruchij.batch.services.snapshots.VideoSnapshotServiceImpl
import com.ruchij.batch.services.sync.SynchronizationServiceImpl
import com.ruchij.batch.services.video.BatchVideoServiceImpl
import com.ruchij.batch.services.worker.WorkExecutorImpl
import com.ruchij.core.daos.doobie.DoobieTransactor
import com.ruchij.core.daos.resource.DoobieFileResourceDao
import com.ruchij.core.daos.scheduling.DoobieSchedulingDao
import com.ruchij.core.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.core.daos.snapshot.DoobieSnapshotDao
import com.ruchij.core.daos.video.DoobieVideoDao
import com.ruchij.core.daos.videometadata.DoobieVideoMetadataDao
import com.ruchij.core.logging.Logger
import com.ruchij.core.messaging.kafka.{KafkaPubSub, KafkaPublisher, KafkaSubscriber}
import com.ruchij.core.messaging.models.HttpMetric
import com.ruchij.core.services.cli.CliCommandRunnerImpl
import com.ruchij.core.services.download.Http4sDownloadService
import com.ruchij.core.services.hashing.MurmurHash3Service
import com.ruchij.core.services.repository.{FileRepositoryService, PathFileTypeDetector}
import com.ruchij.core.services.scheduling.models.{DownloadProgress, WorkerStatusUpdate}
import com.ruchij.core.services.video.{VideoAnalysisServiceImpl, VideoServiceImpl, YouTubeVideoDownloaderImpl}
import com.ruchij.core.types.JodaClock
import doobie.free.connection.ConnectionIO
import fs2.kafka.CommittableConsumerRecord
import org.apache.tika.Tika
import org.http4s.jdkhttpclient.JdkHttpClient
import pureconfig.ConfigSource

import java.net.http.HttpClient
import java.net.http.HttpClient.Redirect
import java.time.Duration

object BatchApp extends IOApp {

  private val logger = Logger[BatchApp.type]

  override def run(args: List[String]): IO[ExitCode] =
    for {
      configObjectSource <- IO.delay(ConfigSource.defaultApplication)
      batchServiceConfiguration <- BatchServiceConfiguration.parse[IO](configObjectSource)

      _ <- program[IO](batchServiceConfiguration)
        .use { scheduler =>
          scheduler.init
            .productR(logger.info[IO]("Scheduler has started"))
            .productR {
              scheduler.run
                .evalMap { video =>
                  logger.info[IO](s"Download completed for videoId = ${video.videoMetadata.id}")
                }
                .compile
                .drain
            }
        }
    } yield ExitCode.Success

  def program[F[+ _]: Async: JodaClock](
    batchServiceConfiguration: BatchServiceConfiguration
  ): Resource[F, Scheduler[F]] =
    DoobieTransactor
      .create[F](batchServiceConfiguration.databaseConfiguration)
      .map(_.trans)
      .flatMap { implicit transaction =>
        for {
          httpClient <- JdkHttpClient[F] {
            HttpClient.newBuilder()
              .followRedirects(Redirect.NORMAL)
              .connectTimeout(Duration.ofHours(24))
              .build()
          }

          dispatcher <- Dispatcher[F]

          workerDao = new DoobieWorkerDao(DoobieSchedulingDao)

          cliCommandRunner = new CliCommandRunnerImpl[F](dispatcher)

          youtubeVideoDownloader = new YouTubeVideoDownloaderImpl[F](cliCommandRunner, httpClient)

          fileTypeDetector = new PathFileTypeDetector[F](new Tika())

          repositoryService = new FileRepositoryService[F](fileTypeDetector)
          downloadService = new Http4sDownloadService[F](httpClient, repositoryService)
          hashingService = new MurmurHash3Service[F]
          videoAnalysisService = new VideoAnalysisServiceImpl[F, ConnectionIO](
            hashingService,
            downloadService,
            youtubeVideoDownloader,
            httpClient,
            DoobieVideoMetadataDao,
            DoobieFileResourceDao,
            batchServiceConfiguration.storageConfiguration
          )

          downloadProgressPublisher <- KafkaPublisher[F, DownloadProgress](batchServiceConfiguration.kafkaConfiguration)
          scheduledVideoDownloadPubSub <- KafkaPubSub[F, ScheduledVideoDownload](
            batchServiceConfiguration.kafkaConfiguration
          )
          workerStatusUpdatesSubscriber = new KafkaSubscriber[F, WorkerStatusUpdate](
            batchServiceConfiguration.kafkaConfiguration
          )
          httpMetricsSubscriber = new KafkaSubscriber[F, HttpMetric](batchServiceConfiguration.kafkaConfiguration)

          batchSchedulingService = new BatchSchedulingServiceImpl[F, ConnectionIO, CommittableConsumerRecord[
            F,
            Unit,
            *
          ]](
            downloadProgressPublisher,
            workerStatusUpdatesSubscriber,
            scheduledVideoDownloadPubSub,
            DoobieSchedulingDao
          )

          videoService = new VideoServiceImpl[F, ConnectionIO](
            repositoryService,
            DoobieVideoDao,
            DoobieSnapshotDao,
            DoobieFileResourceDao
          )

          batchVideoService = new BatchVideoServiceImpl[F, ConnectionIO](
            videoService,
            DoobieVideoDao,
            DoobieVideoMetadataDao,
            DoobieFileResourceDao
          )

          videoSnapshotService = new VideoSnapshotServiceImpl[F](cliCommandRunner, repositoryService, hashingService)

          videoEnrichmentService = new VideoEnrichmentServiceImpl[F, ConnectionIO](
            videoSnapshotService,
            DoobieSnapshotDao,
            DoobieFileResourceDao,
            batchServiceConfiguration.storageConfiguration
          )

          synchronizationService = new SynchronizationServiceImpl[F, repositoryService.BackedType, ConnectionIO](
            repositoryService,
            DoobieFileResourceDao,
            DoobieVideoMetadataDao,
            DoobieSchedulingDao,
            DoobieFileSyncDao,
            DoobieVideoDao,
            batchVideoService,
            videoEnrichmentService,
            hashingService,
            cliCommandRunner,
            fileTypeDetector,
            batchServiceConfiguration.storageConfiguration
          )

          workExecutor = new WorkExecutorImpl[F, ConnectionIO](
            DoobieFileResourceDao,
            workerDao,
            DoobieVideoMetadataDao,
            repositoryService,
            batchSchedulingService,
            videoAnalysisService,
            batchVideoService,
            downloadService,
            youtubeVideoDownloader,
            videoEnrichmentService,
            batchServiceConfiguration.storageConfiguration
          )

          scheduler = new SchedulerImpl(
            batchSchedulingService,
            synchronizationService,
            batchVideoService,
            workExecutor,
            httpMetricsSubscriber,
            workerDao,
            batchServiceConfiguration.workerConfiguration,
            batchServiceConfiguration.applicationInformation.instanceId
          )
        } yield scheduler
      }
}
