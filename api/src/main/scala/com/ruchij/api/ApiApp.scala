package com.ruchij.api

import cats.effect._
import cats.effect.kernel.Async
import cats.effect.std.Dispatcher
import cats.implicits._
import cats.~>
import com.eed3si9n.ruchij.api.BuildInfo
import com.ruchij.api.config.ApiServiceConfiguration
import com.ruchij.api.daos.credentials.DoobieCredentialsDao
import com.ruchij.api.daos.playlist.DoobiePlaylistDao
import com.ruchij.api.daos.resettoken.DoobieCredentialsResetTokenDao
import com.ruchij.api.daos.user.DoobieUserDao
import com.ruchij.api.models.ApiMessageBrokers
import com.ruchij.api.services.asset.AssetServiceImpl
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
import com.ruchij.core.commands.ScanVideosCommand
import com.ruchij.core.daos.doobie.DoobieTransactor
import com.ruchij.core.daos.permission.DoobieVideoPermissionDao
import com.ruchij.core.daos.resource.DoobieFileResourceDao
import com.ruchij.core.daos.scheduling.DoobieSchedulingDao
import com.ruchij.core.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.core.daos.snapshot.DoobieSnapshotDao
import com.ruchij.core.daos.title.DoobieVideoTitleDao
import com.ruchij.core.daos.video.DoobieVideoDao
import com.ruchij.core.daos.videometadata.DoobieVideoMetadataDao
import com.ruchij.core.daos.videowatchhistory.DoobieVideoWatchHistoryDao
import com.ruchij.core.kv.keys.KeySpacedKeyEncoder.keySpacedKeyEncoder
import com.ruchij.core.kv.{KeySpacedKeyValueStore, KeyValueStore, RedisKeyValueStore}
import com.ruchij.core.logging.Logger
import com.ruchij.core.messaging.kafka.{KafkaPubSub, KafkaPublisher}
import com.ruchij.core.messaging.models.{HttpMetric, VideoWatchMetric}
import com.ruchij.core.services.cli.CliCommandRunnerImpl
import com.ruchij.core.services.config.{ConfigurationService, ConfigurationServiceImpl}
import com.ruchij.core.services.download.Http4sDownloadService
import com.ruchij.core.services.hashing.MurmurHash3Service
import com.ruchij.core.services.renderer.SpaSiteRendererImpl
import com.ruchij.core.services.repository.{FileRepositoryService, PathFileTypeDetector}
import com.ruchij.core.services.scheduling.models.{DownloadProgress, WorkerStatusUpdate}
import com.ruchij.core.services.video.{
  VideoAnalysisServiceImpl,
  VideoServiceImpl,
  VideoWatchHistoryServiceImpl,
  YouTubeVideoDownloaderImpl
}
import com.ruchij.core.types.{JodaClock, RandomGenerator}
import doobie.free.connection.ConnectionIO
import doobie.hikari.HikariTransactor
import fs2.compression.Compression
import fs2.io.file.Files
import fs2.kafka.CommittableConsumerRecord
import org.apache.tika.Tika
import org.http4s.HttpApp
import org.http4s.client.Client
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.jdkhttpclient.JdkHttpClient
import org.joda.time.DateTime
import pureconfig.ConfigSource

import java.net.http.HttpClient
import java.net.http.HttpClient.Redirect
import java.util.UUID

object ApiApp extends IOApp {
  private val logger = Logger[ApiApp.type]

  override def run(args: List[String]): IO[ExitCode] =
    for {
      _ <- logger.info[IO](BuildInfo.toString)

      configObjectSource <- IO.delay(ConfigSource.defaultApplication)
      apiServiceConfiguration <- ApiServiceConfiguration.parse[IO](configObjectSource)

      _ <- create[IO](apiServiceConfiguration)
        .flatMap { httpApp =>
          EmberServerBuilder
            .default[IO]
            .withHttpApp(httpApp)
            .withHost(apiServiceConfiguration.httpConfiguration.host)
            .withPort(apiServiceConfiguration.httpConfiguration.port)
            .build
        }
        .use(_ => IO.never)
    } yield ExitCode.Success

  def create[F[_]: Async: JodaClock: Files: Compression](
    apiServiceConfiguration: ApiServiceConfiguration
  ): Resource[F, HttpApp[F]] =
    for {
      hikariTransactor <- DoobieTransactor.create[F](apiServiceConfiguration.databaseConfiguration)

      javaHttpClient <- Resource.eval {
        Sync[F].blocking {
          HttpClient.newBuilder().followRedirects(Redirect.NORMAL).build()
        }
      }
      httpClient = JdkHttpClient(javaHttpClient)

      redisKeyValueStore <- RedisKeyValueStore.create[F](apiServiceConfiguration.redisConfiguration)
      downloadProgressPubSub <- KafkaPubSub[F, DownloadProgress](apiServiceConfiguration.kafkaConfiguration)
      scheduledVideoDownloadPubSub <- KafkaPubSub[F, ScheduledVideoDownload](apiServiceConfiguration.kafkaConfiguration)
      healthCheckPubSub <- KafkaPubSub[F, HealthCheckMessage](apiServiceConfiguration.kafkaConfiguration)
      httpMetricsPublisher <- KafkaPublisher[F, HttpMetric](apiServiceConfiguration.kafkaConfiguration)
      videoWatchMetricsPublisher <- KafkaPublisher[F, VideoWatchMetric](apiServiceConfiguration.kafkaConfiguration)
      workerStatusUpdatePublisher <- KafkaPublisher[F, WorkerStatusUpdate](apiServiceConfiguration.kafkaConfiguration)
      scanVideoCommandPublisher <- KafkaPublisher[F, ScanVideosCommand](apiServiceConfiguration.kafkaConfiguration)
      dispatcher <- Dispatcher.parallel[F]

      messageBrokers = ApiMessageBrokers(
        downloadProgressPubSub,
        scheduledVideoDownloadPubSub,
        healthCheckPubSub,
        workerStatusUpdatePublisher,
        scanVideoCommandPublisher,
        httpMetricsPublisher,
        videoWatchMetricsPublisher
      )

      httpApp <- Resource.eval {
        program[F, CommittableConsumerRecord[F, Unit, *]](
          hikariTransactor,
          httpClient,
          redisKeyValueStore,
          messageBrokers,
          dispatcher,
          apiServiceConfiguration
        )
      }
    } yield httpApp

  def program[F[_]: Async: JodaClock: Files: Compression, M[_]](
    hikariTransactor: HikariTransactor[F],
    client: Client[F],
    keyValueStore: KeyValueStore[F],
    messageBrokers: ApiMessageBrokers[F, M],
    dispatcher: Dispatcher[F],
    apiServiceConfiguration: ApiServiceConfiguration
  ): F[HttpApp[F]] = {
    implicit val transactor: ConnectionIO ~> F = hikariTransactor.trans

    val healthCheckKeyStore: KeySpacedKeyValueStore[F, HealthCheckKey, DateTime] =
      new KeySpacedKeyValueStore(HealthCheckKeySpace, keyValueStore)

    val authenticationKeyStore
      : KeySpacedKeyValueStore[F, AuthenticationToken.AuthenticationTokenKey, AuthenticationToken] =
      new KeySpacedKeyValueStore(AuthenticationKeySpace, keyValueStore)

    val configurationService: ConfigurationService[F, ApiConfigKey] =
      new ConfigurationServiceImpl[F, ApiConfigKey](
        new KeySpacedKeyValueStore[F, ApiConfigKey[_], String](ApiConfigKeySpace, keyValueStore)
      )

    val fileTypeDetector = new PathFileTypeDetector[F](new Tika())

    val repositoryService: FileRepositoryService[F] = new FileRepositoryService[F](fileTypeDetector)
    val downloadService: Http4sDownloadService[F] = new Http4sDownloadService[F](client, repositoryService)
    val hashingService: MurmurHash3Service[F] = new MurmurHash3Service[F]
    val passwordHashingService = new BCryptPasswordHashingService[F]
    val cliCommandRunner = new CliCommandRunnerImpl[F](dispatcher)

    val authenticationService: AuthenticationService[F] =
      new AuthenticationServiceImpl[F, ConnectionIO](
        authenticationKeyStore,
        passwordHashingService,
        DoobieUserDao,
        DoobieCredentialsDao,
        apiServiceConfiguration.authenticationConfiguration.sessionDuration
      )

    val youTubeVideoDownloader = new YouTubeVideoDownloaderImpl[F](cliCommandRunner, client)

    val spaSiteRenderer = new SpaSiteRendererImpl[F](client, apiServiceConfiguration.spaSiteRendererConfiguration)

    val videoAnalysisService: VideoAnalysisServiceImpl[F, ConnectionIO] =
      new VideoAnalysisServiceImpl[F, ConnectionIO](
        hashingService,
        downloadService,
        youTubeVideoDownloader,
        client,
        spaSiteRenderer,
        cliCommandRunner,
        DoobieVideoMetadataDao,
        DoobieFileResourceDao,
        apiServiceConfiguration.storageConfiguration
      )

    val videoService =
      new VideoServiceImpl[F, ConnectionIO](
        repositoryService,
        DoobieVideoDao,
        DoobieVideoWatchHistoryDao,
        DoobieSnapshotDao,
        DoobieFileResourceDao,
        DoobieVideoTitleDao,
        DoobieVideoPermissionDao,
        DoobieSchedulingDao
      )

    val apiVideoService = new ApiVideoServiceImpl[F, ConnectionIO](
      videoService,
      messageBrokers.scanVideosCommandPublisher,
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
      new AssetServiceImpl[F, ConnectionIO](
        DoobieFileResourceDao,
        DoobieSnapshotDao,
        DoobieVideoDao,
        repositoryService,
        messageBrokers.videoWatchMetricsPublisher
      )

    val schedulingService = new ApiSchedulingServiceImpl[F, ConnectionIO](
      videoAnalysisService,
      messageBrokers.scheduledVideoDownloadPubSub,
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
      DoobieCredentialsResetTokenDao,
      DoobieVideoTitleDao,
      DoobieVideoPermissionDao
    )

    val videoWatchHistoryService =
      new VideoWatchHistoryServiceImpl[F, ConnectionIO](DoobieVideoWatchHistoryDao)

    for {
      instanceId <- RandomGenerator[F, UUID].generate.map(_.toString)

      backgroundService <- BackgroundServiceImpl.create[F, M](
        schedulingService,
        messageBrokers.downloadProgressSubscriber,
        messageBrokers.healthCheckPubSub,
        messageBrokers.scheduledVideoDownloadPubSub,
        s"background-$instanceId"
      )

      healthService = new HealthServiceImpl[F](
        repositoryService,
        healthCheckKeyStore,
        backgroundService.healthChecks,
        messageBrokers.healthCheckPubSub,
        client,
        apiServiceConfiguration.storageConfiguration,
        apiServiceConfiguration.spaSiteRendererConfiguration
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
        videoWatchHistoryService,
        healthService,
        authenticationService,
        backgroundService.downloadProgress,
        backgroundService.updates,
        messageBrokers.httpMetricsPublisher,
        apiServiceConfiguration.httpConfiguration.allowedOriginHosts
      )
  }

}
