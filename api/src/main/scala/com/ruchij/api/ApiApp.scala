package com.ruchij.api

import cats.effect.{Blocker, ConcurrentEffect, ContextShift, ExitCode, IO, IOApp, Resource, Sync, Timer}
import cats.implicits._
import cats.~>
import com.ruchij.api.config.ApiServiceConfiguration
import com.ruchij.api.daos.credentials.DoobieCredentialsDao
import com.ruchij.api.daos.permission.DoobieVideoPermissionDao
import com.ruchij.api.daos.playlist.DoobiePlaylistDao
import com.ruchij.api.daos.resettoken.DoobieCredentialsResetToken
import com.ruchij.api.daos.user.DoobieUserDao
import com.ruchij.api.models.ApiMessageBrokers
import com.ruchij.api.services.authentication._
import com.ruchij.api.services.authentication.models.AuthenticationToken
import com.ruchij.api.services.authentication.models.AuthenticationToken.AuthenticationKeySpace
import com.ruchij.api.services.background.BackgroundServiceImpl
import com.ruchij.api.services.config.models.ApiConfigKey
import com.ruchij.api.services.config.models.ApiConfigKey.{ApiConfigKeySpace, apiConfigKeySpacedKVEncoder}
import com.ruchij.api.services.hashing.BCryptPasswordHashingService
import com.ruchij.api.services.health.HealthServiceImpl
import com.ruchij.api.services.health.models.kv.HealthCheckKey
import com.ruchij.api.services.health.models.kv.HealthCheckKey.HealthCheckKeySpace
import com.ruchij.api.services.health.models.messaging.HealthCheckMessage
import com.ruchij.api.services.playlist.PlaylistServiceImpl
import com.ruchij.api.services.scheduling.ApiSchedulingServiceImpl
import com.ruchij.api.services.user.UserServiceImpl
import com.ruchij.api.services.video.ApiVideoServiceImpl
import com.ruchij.api.web.Routes
import com.ruchij.core.daos.doobie.DoobieTransactor
import com.ruchij.core.daos.resource.DoobieFileResourceDao
import com.ruchij.core.daos.scheduling.DoobieSchedulingDao
import com.ruchij.core.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.core.daos.snapshot.DoobieSnapshotDao
import com.ruchij.api.daos.title.DoobieVideoTitleDao
import com.ruchij.core.daos.video.DoobieVideoDao
import com.ruchij.core.daos.videometadata.DoobieVideoMetadataDao
import com.ruchij.core.kv.keys.KeySpacedKeyEncoder.keySpacedKeyEncoder
import com.ruchij.core.kv.{KeySpacedKeyValueStore, KeyValueStore, RedisKeyValueStore}
import com.ruchij.core.messaging.kafka.{KafkaPubSub, KafkaPublisher}
import com.ruchij.core.messaging.models.HttpMetric
import com.ruchij.core.services.asset.AssetServiceImpl
import com.ruchij.core.services.cli.CliCommandRunnerImpl
import com.ruchij.core.services.config.{ConfigurationService, ConfigurationServiceImpl}
import com.ruchij.core.services.download.Http4sDownloadService
import com.ruchij.core.services.hashing.MurmurHash3Service
import com.ruchij.core.services.repository.{FileRepositoryService, PathFileTypeDetector}
import com.ruchij.core.services.scheduling.models.{DownloadProgress, WorkerStatusUpdate}
import com.ruchij.core.services.video.{VideoAnalysisServiceImpl, VideoServiceImpl, YouTubeVideoDownloaderImpl}
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.effect.Log.Stdout.instance
import doobie.free.connection.ConnectionIO
import fs2.kafka.CommittableConsumerRecord
import org.apache.tika.Tika
import org.http4s.HttpApp
import org.http4s.asynchttpclient.client.AsyncHttpClient
import org.http4s.blaze.server.BlazeServerBuilder
import org.http4s.client.Client
import org.http4s.client.middleware.FollowRedirect
import org.joda.time.DateTime
import pureconfig.ConfigSource

import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext

object ApiApp extends IOApp {

  override def run(args: List[String]): IO[ExitCode] =
    for {
      configObjectSource <- IO.delay(ConfigSource.defaultApplication)
      webServiceConfiguration <- ApiServiceConfiguration.parse[IO](configObjectSource)

      _ <- create[IO](webServiceConfiguration)
        .use { httpApp =>
          BlazeServerBuilder[IO](ExecutionContext.global)
            .withHttpApp(httpApp)
            .withoutBanner
            .bindHttp(webServiceConfiguration.httpConfiguration.port, webServiceConfiguration.httpConfiguration.host)
            .serve
            .compile
            .drain
        }
    } yield ExitCode.Success

  def create[F[+ _]: ConcurrentEffect: Timer: ContextShift](
    apiServiceConfiguration: ApiServiceConfiguration
  ): Resource[F, HttpApp[F]] =
    DoobieTransactor
      .create[F](apiServiceConfiguration.databaseConfiguration)
      .map(_.trans)
      .flatMap { implicit transaction =>
        for {
          httpClient <- AsyncHttpClient.resource().map(FollowRedirect(maxRedirects = 10))

          threadPoolIO <- Resource.make(Sync[F].delay(Executors.newCachedThreadPool())) { executorService =>
            Sync[F].delay(executorService.shutdown())
          }
          blockerIO = Blocker.liftExecutionContext(ExecutionContext.fromExecutor(threadPoolIO))

          processorCount <- Resource.eval(Sync[F].delay(Runtime.getRuntime.availableProcessors()))
          blockingThreadPool <- Resource.make(Sync[F].delay(Executors.newFixedThreadPool(processorCount))) {
            executorService =>
              Sync[F].delay(executorService.shutdown())
          }
          blockerCPU = Blocker.liftExecutionContext(ExecutionContext.fromExecutor(blockingThreadPool))

          redisCommands <- Redis[F].utf8(apiServiceConfiguration.redisConfiguration.uri)
          redisKeyValueStore = new RedisKeyValueStore[F](redisCommands)

          downloadProgressPubSub <- KafkaPubSub[F, DownloadProgress](apiServiceConfiguration.kafkaConfiguration)
          scheduledVideoDownloadPubSub <- KafkaPubSub[F, ScheduledVideoDownload](
            apiServiceConfiguration.kafkaConfiguration
          )
          healthCheckPubSub <- KafkaPubSub[F, HealthCheckMessage](apiServiceConfiguration.kafkaConfiguration)
          metricsPublisher <- KafkaPublisher[F, HttpMetric](apiServiceConfiguration.kafkaConfiguration)
          workerStatusUpdatePubSub <- KafkaPubSub[F, WorkerStatusUpdate](apiServiceConfiguration.kafkaConfiguration)

          messageBrokers = ApiMessageBrokers(
            downloadProgressPubSub,
            scheduledVideoDownloadPubSub,
            healthCheckPubSub,
            workerStatusUpdatePubSub,
            metricsPublisher
          )

          httpApp <- Resource.eval {
            program[F, CommittableConsumerRecord[F, Unit, *]](
              httpClient,
              redisKeyValueStore,
              messageBrokers,
              blockerIO,
              blockerCPU,
              apiServiceConfiguration
            )
          }
        } yield httpApp
      }

  def program[F[+ _]: ConcurrentEffect: ContextShift: Timer, M[_]](
    client: Client[F],
    keyValueStore: KeyValueStore[F],
    messageBrokers: ApiMessageBrokers[F, M],
    blockerIO: Blocker,
    blockerCPU: Blocker,
    apiServiceConfiguration: ApiServiceConfiguration
  )(implicit transaction: ConnectionIO ~> F): F[HttpApp[F]] = {
    val healthCheckKeyStore: KeySpacedKeyValueStore[F, HealthCheckKey, DateTime] =
      new KeySpacedKeyValueStore(HealthCheckKeySpace, keyValueStore)

    val authenticationKeyStore
      : KeySpacedKeyValueStore[F, AuthenticationToken.AuthenticationTokenKey, AuthenticationToken] =
      new KeySpacedKeyValueStore(AuthenticationKeySpace, keyValueStore)

    val configurationService: ConfigurationService[F, ApiConfigKey] =
      new ConfigurationServiceImpl[F, ApiConfigKey](
        new KeySpacedKeyValueStore[F, ApiConfigKey[_], String](ApiConfigKeySpace, keyValueStore)
      )

    val fileTypeDetector = new PathFileTypeDetector[F](new Tika(), blockerIO)

    val repositoryService: FileRepositoryService[F] = new FileRepositoryService[F](fileTypeDetector, blockerIO)
    val downloadService: Http4sDownloadService[F] = new Http4sDownloadService[F](client, repositoryService)
    val hashingService: MurmurHash3Service[F] = new MurmurHash3Service[F](blockerCPU)
    val passwordHashingService = new BCryptPasswordHashingService[F](blockerCPU)

    val authenticationService: AuthenticationService[F] =
      new AuthenticationServiceImpl[F, ConnectionIO](
        authenticationKeyStore,
        passwordHashingService,
        DoobieUserDao,
        DoobieCredentialsDao,
        apiServiceConfiguration.authenticationConfiguration.sessionDuration
      )

    val youTubeVideoDownloader = new YouTubeVideoDownloaderImpl[F](new CliCommandRunnerImpl[F], client)

    val videoAnalysisService: VideoAnalysisServiceImpl[F, ConnectionIO] =
      new VideoAnalysisServiceImpl[F, ConnectionIO](
        hashingService,
        downloadService,
        youTubeVideoDownloader,
        client,
        DoobieVideoMetadataDao,
        DoobieFileResourceDao,
        apiServiceConfiguration.storageConfiguration
      )

    val videoService = new VideoServiceImpl[F, ConnectionIO](
      repositoryService,
      DoobieVideoDao,
      DoobieVideoMetadataDao,
      DoobieSnapshotDao,
      DoobieFileResourceDao,
      DoobieSchedulingDao
    )

    val apiVideoService = new ApiVideoServiceImpl[F, ConnectionIO](
      videoService,
      DoobieVideoDao,
      DoobieVideoMetadataDao,
      DoobieSnapshotDao,
      DoobieVideoTitleDao,
      DoobieVideoPermissionDao
    )

    val playlistDao = new DoobiePlaylistDao(DoobieFileResourceDao, DoobieVideoDao)

    val playlistService = new PlaylistServiceImpl[F, ConnectionIO](
      playlistDao,
      DoobieFileResourceDao,
      repositoryService,
      apiServiceConfiguration.storageConfiguration
    )

    val assetService =
      new AssetServiceImpl[F, ConnectionIO](DoobieFileResourceDao, DoobieSnapshotDao, DoobieVideoDao, repositoryService)

    val schedulingService = new ApiSchedulingServiceImpl[F, ConnectionIO](
      videoAnalysisService,
      messageBrokers.scheduledVideoDownloadPublisher,
      messageBrokers.workerStatusUpdatesPublisher,
      configurationService,
      DoobieSchedulingDao,
      DoobieVideoTitleDao,
      DoobieVideoPermissionDao
    )

    val userService = new UserServiceImpl[F, ConnectionIO](
      passwordHashingService,
      DoobieUserDao,
      DoobieCredentialsDao,
      DoobieCredentialsResetToken,
      DoobieVideoTitleDao,
      DoobieVideoPermissionDao
    )

    for {
      backgroundService <- BackgroundServiceImpl.create[F, M](
        schedulingService,
        messageBrokers.downloadProgressSubscriber,
        messageBrokers.healthCheckPubSub,
        s"background-${apiServiceConfiguration.applicationInformation.instanceId}"
      )

      healthService = new HealthServiceImpl[F](
        repositoryService,
        healthCheckKeyStore,
        backgroundService.healthChecks,
        messageBrokers.healthCheckPubSub,
        apiServiceConfiguration.applicationInformation,
        apiServiceConfiguration.storageConfiguration
      )

      _ <- backgroundService.run

    } yield
      Routes(
        userService,
        apiVideoService,
        videoAnalysisService,
        schedulingService,
        playlistService,
        assetService,
        healthService,
        authenticationService,
        backgroundService.downloadProgress,
        messageBrokers.metricsPublisher,
        blockerIO
      )
  }

}
