package com.ruchij.api

import cats.effect.{Blocker, Concurrent, ConcurrentEffect, ContextShift, ExitCode, IO, IOApp, Resource, Sync, Timer}
import cats.~>
import com.ruchij.api.config.AuthenticationConfiguration.PasswordAuthenticationConfiguration
import com.ruchij.api.config.{ApiServiceConfiguration, AuthenticationConfiguration}
import com.ruchij.api.models.ApiMessageBrokers
import com.ruchij.api.services.authentication.models.AuthenticationToken
import com.ruchij.api.services.authentication.models.AuthenticationToken.AuthenticationKeySpace
import com.ruchij.api.services.authentication._
import com.ruchij.api.services.background.BackgroundServiceImpl
import com.ruchij.api.services.health.HealthServiceImpl
import com.ruchij.api.services.health.models.kv.HealthCheckKey
import com.ruchij.api.services.health.models.kv.HealthCheckKey.HealthCheckKeySpace
import com.ruchij.api.services.health.models.messaging.HealthCheckMessage
import com.ruchij.api.web.Routes
import com.ruchij.core.daos.doobie.DoobieTransactor
import com.ruchij.core.daos.resource.DoobieFileResourceDao
import com.ruchij.core.daos.scheduling.DoobieSchedulingDao
import com.ruchij.core.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.core.daos.snapshot.DoobieSnapshotDao
import com.ruchij.core.daos.video.DoobieVideoDao
import com.ruchij.core.daos.videometadata.DoobieVideoMetadataDao
import com.ruchij.core.kv.keys.KeySpacedKeyEncoder.keySpacedKeyEncoder
import com.ruchij.core.kv.{KeySpacedKeyValueStore, KeyValueStore, RedisKeyValueStore}
import com.ruchij.core.messaging.kafka.{KafkaPubSub, KafkaPublisher}
import com.ruchij.core.messaging.models.HttpMetric
import com.ruchij.core.services.asset.AssetServiceImpl
import com.ruchij.core.services.download.Http4sDownloadService
import com.ruchij.core.services.hashing.MurmurHash3Service
import com.ruchij.core.services.repository.FileRepositoryService
import com.ruchij.core.services.scheduling.SchedulingServiceImpl
import com.ruchij.core.services.scheduling.models.DownloadProgress
import com.ruchij.core.services.video.{VideoAnalysisServiceImpl, VideoServiceImpl}
import com.ruchij.migration.MigrationApp
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.effect.Log.Stdout.instance
import doobie.free.connection.ConnectionIO
import fs2.concurrent.Topic
import org.http4s.HttpApp
import org.http4s.client.Client
import org.http4s.asynchttpclient.client.AsyncHttpClient
import org.http4s.client.middleware.FollowRedirect
import org.http4s.blaze.server.BlazeServerBuilder
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

          threadPoolIO <-
            Resource.make(Sync[F].delay(Executors.newCachedThreadPool())) {
              executorService => Sync[F].delay(executorService.shutdown())
            }
          blockerIO = Blocker.liftExecutionContext(ExecutionContext.fromExecutor(threadPoolIO))

          processorCount <- Resource.eval(Sync[F].delay(Runtime.getRuntime.availableProcessors()))
          blockingThreadPool <-
            Resource.make(Sync[F].delay(Executors.newFixedThreadPool(processorCount))) {
              executorService => Sync[F].delay(executorService.shutdown())
            }
          blockerCPU = Blocker.liftExecutionContext(ExecutionContext.fromExecutor(blockingThreadPool))

          redisCommands <- Redis[F].utf8(apiServiceConfiguration.redisConfiguration.uri)
          redisKeyValueStore = new RedisKeyValueStore[F](redisCommands)

          downloadProgressPubSub <- KafkaPubSub[F, DownloadProgress](apiServiceConfiguration.kafkaConfiguration)
          scheduledVideoDownloadPubSub <- KafkaPubSub[F, ScheduledVideoDownload](apiServiceConfiguration.kafkaConfiguration)
          healthCheckPubSub <- KafkaPubSub[F, HealthCheckMessage](apiServiceConfiguration.kafkaConfiguration)
          metricsPublisher <- KafkaPublisher[F, HttpMetric](apiServiceConfiguration.kafkaConfiguration)

          messageBrokers =
            ApiMessageBrokers(downloadProgressPubSub, scheduledVideoDownloadPubSub, healthCheckPubSub, metricsPublisher)

          httpApp <-
            program[F](httpClient, redisKeyValueStore, messageBrokers, blockerIO, blockerCPU, apiServiceConfiguration)
        }
        yield httpApp
      }

  def program[F[+ _]: Concurrent: ContextShift: Timer](
    client: Client[F],
    keyValueStore: KeyValueStore[F],
    messageBrokers: ApiMessageBrokers[F],
    blockerIO: Blocker,
    blockerCPU: Blocker,
    apiServiceConfiguration: ApiServiceConfiguration
  )(implicit transaction: ConnectionIO ~> F): Resource[F, HttpApp[F]] = {
    val healthCheckKeyStore: KeySpacedKeyValueStore[F, HealthCheckKey, DateTime] =
      new KeySpacedKeyValueStore(HealthCheckKeySpace, keyValueStore)

    val authenticationKeyStore: KeySpacedKeyValueStore[F, AuthenticationToken.AuthenticationTokenKey, AuthenticationToken] =
      new KeySpacedKeyValueStore(AuthenticationKeySpace, keyValueStore)

    val repositoryService: FileRepositoryService[F] = new FileRepositoryService[F](blockerIO)
    val downloadService: Http4sDownloadService[F] = new Http4sDownloadService[F](client, repositoryService)
    val hashingService: MurmurHash3Service[F] = new MurmurHash3Service[F](blockerCPU)

    val authenticationService: AuthenticationService[F] =
      apiServiceConfiguration.authenticationConfiguration match {
        case AuthenticationConfiguration.NoAuthenticationConfiguration =>
          new NoAuthenticationService[F]

        case passwordAuthenticationConfiguration: PasswordAuthenticationConfiguration =>
          new AuthenticationServiceImpl[F](authenticationKeyStore, passwordAuthenticationConfiguration, blockerCPU)
      }

    val videoAnalysisService: VideoAnalysisServiceImpl[F, ConnectionIO] =
      new VideoAnalysisServiceImpl[F, ConnectionIO](
        hashingService,
        downloadService,
        client,
        DoobieVideoMetadataDao,
        DoobieFileResourceDao,
        apiServiceConfiguration.storageConfiguration
      )

    val videoService: VideoServiceImpl[F, ConnectionIO] =
      new VideoServiceImpl[F, ConnectionIO](
      repositoryService,
      DoobieVideoDao,
      DoobieVideoMetadataDao,
      DoobieSnapshotDao,
      DoobieSchedulingDao,
      DoobieFileResourceDao
    )

    val assetService = new AssetServiceImpl[F, ConnectionIO](DoobieFileResourceDao, repositoryService)

    val schedulingService = new SchedulingServiceImpl[F, ConnectionIO](
      videoAnalysisService,
      DoobieSchedulingDao,
      messageBrokers.downloadProgressPubSub,
      messageBrokers.scheduledVideoDownloadPubSub
    )

    val healthService = new HealthServiceImpl[F](
      repositoryService,
      healthCheckKeyStore,
      messageBrokers.healthCheckPubSub,
      apiServiceConfiguration.applicationInformation,
      apiServiceConfiguration.storageConfiguration
    )

    val backgroundService = new BackgroundServiceImpl[F](
      messageBrokers.downloadProgressPubSub,
      schedulingService,
      s"background-${apiServiceConfiguration.applicationInformation.instanceId}"
    )

    for {
      _ <- Resource.eval(MigrationApp.migration[F](apiServiceConfiguration.databaseConfiguration, blockerIO))

      _ <- Resource.make(Concurrent[F].start(backgroundService.run))(_.cancel)

      topic <- Resource.eval(Topic[F, Option[DownloadProgress]](None))

      _ <-
        Resource.make {
          Concurrent[F].start {
            schedulingService
              .subscribeToDownloadProgress(apiServiceConfiguration.applicationInformation.instanceId)
              .through(stream => topic.publish(stream.map(Some.apply)))
              .compile
              .drain
          }
        }(_.cancel)

      downloadProgressStream =
        topic
          .subscribe(Int.MaxValue)
          .collect { case Some(downloadProgress) => downloadProgress }

    } yield
      Routes(
        videoService,
        videoAnalysisService,
        schedulingService,
        assetService,
        healthService,
        authenticationService,
        downloadProgressStream,
        messageBrokers.metricsPublisher,
        blockerIO
      )
  }

}
