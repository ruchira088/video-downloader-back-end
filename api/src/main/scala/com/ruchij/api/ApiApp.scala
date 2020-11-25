package com.ruchij.api

import java.util.concurrent.Executors

import cats.effect.{Blocker, Concurrent, ConcurrentEffect, ContextShift, ExitCode, IO, IOApp, Resource, Sync, Timer}
import com.ruchij.api.config.{ApiServiceConfiguration, AuthenticationConfiguration}
import com.ruchij.api.config.AuthenticationConfiguration.PasswordAuthenticationConfiguration
import com.ruchij.api.services.authentication.{AuthenticationServiceImpl, NoAuthenticationService}
import com.ruchij.api.services.authentication.models.AuthenticationToken.AuthenticationKeySpace
import com.ruchij.api.services.background.BackgroundServiceImpl
import com.ruchij.api.services.health.HealthServiceImpl
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
import com.ruchij.core.kv.{KeySpacedKeyValueStore, RedisKeyValueStore}
import com.ruchij.core.kv.keys.KVStoreKey.{kvStoreKeyDecoder, kvStoreKeyEncoder}
import com.ruchij.core.logging.Logger
import com.ruchij.core.messaging.kafka.KafkaPubSub
import com.ruchij.core.services.asset.AssetServiceImpl
import com.ruchij.core.services.download.Http4sDownloadService
import com.ruchij.core.services.hashing.MurmurHash3Service
import com.ruchij.core.services.repository.FileRepositoryService
import com.ruchij.core.services.scheduling.SchedulingServiceImpl
import com.ruchij.core.services.scheduling.models.DownloadProgress
import com.ruchij.core.services.video.{VideoAnalysisServiceImpl, VideoServiceImpl}
import com.ruchij.core.types.FunctionKTypes
import com.ruchij.migration.MigrationApp
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.effect.Log.Stdout.instance
import doobie.free.connection.ConnectionIO
import org.http4s.HttpApp
import org.http4s.client.asynchttpclient.AsyncHttpClient
import org.http4s.client.middleware.FollowRedirect
import org.http4s.server.blaze.BlazeServerBuilder
import pureconfig.ConfigSource

import scala.concurrent.ExecutionContext

object ApiApp extends IOApp {
  override def run(args: List[String]): IO[ExitCode] =
    for {
      configObjectSource <- IO.delay(ConfigSource.defaultApplication)
      webServiceConfiguration <- ApiServiceConfiguration.parse[IO](configObjectSource)

      _ <- program[IO](webServiceConfiguration)
        .use { httpApp =>
          BlazeServerBuilder[IO](ExecutionContext.global)
            .withHttpApp(httpApp)
            .bindHttp(webServiceConfiguration.httpConfiguration.port, webServiceConfiguration.httpConfiguration.host)
            .serve
            .compile
            .drain
        }
    } yield ExitCode.Success

  def program[F[+ _]: ConcurrentEffect: Timer: ContextShift](
    apiServiceConfiguration: ApiServiceConfiguration
  ): Resource[F, HttpApp[F]] =
    Resource
      .liftF(DoobieTransactor.create[F](apiServiceConfiguration.databaseConfiguration))
      .map(FunctionKTypes.transaction[F])
      .flatMap { implicit transaction =>
        for {
          httpClient <- AsyncHttpClient.resource().map(FollowRedirect(maxRedirects = 10))

          ioThreadPool <- Resource.liftF(Sync[F].delay(Executors.newCachedThreadPool()))
          ioBlocker = Blocker.liftExecutionContext(ExecutionContext.fromExecutor(ioThreadPool))

          processorCount <- Resource.liftF(Sync[F].delay(Runtime.getRuntime.availableProcessors()))
          cpuBlockingThreadPool <- Resource.liftF(Sync[F].delay(Executors.newFixedThreadPool(processorCount)))
          cpuBlocker = Blocker.liftExecutionContext(ExecutionContext.fromExecutor(cpuBlockingThreadPool))

          redisCommands <- Redis[F].utf8(apiServiceConfiguration.redisConfiguration.uri)
          keyValueStore = new RedisKeyValueStore[F](redisCommands)

          healthCheckKeyStore = new KeySpacedKeyValueStore(HealthCheckKeySpace, keyValueStore)
          authenticationKeyStore = new KeySpacedKeyValueStore(AuthenticationKeySpace, keyValueStore)

          _ <- Resource.liftF(MigrationApp.migration[F](apiServiceConfiguration.databaseConfiguration, ioBlocker))

          repositoryService = new FileRepositoryService[F](ioBlocker)
          downloadService = new Http4sDownloadService[F](httpClient, repositoryService)

          hashingService = new MurmurHash3Service[F](cpuBlocker)
          videoAnalysisService = new VideoAnalysisServiceImpl[F, ConnectionIO](
            hashingService,
            downloadService,
            httpClient,
            DoobieVideoMetadataDao,
            DoobieFileResourceDao,
            apiServiceConfiguration.downloadConfiguration
          )

          videoService = new VideoServiceImpl[F, ConnectionIO](
            DoobieVideoDao,
            DoobieVideoMetadataDao,
            DoobieSnapshotDao,
            DoobieSchedulingDao,
            DoobieFileResourceDao
          )

          assetService = new AssetServiceImpl[F, ConnectionIO](DoobieFileResourceDao, repositoryService)

          downloadProgressPubSub <- KafkaPubSub[F, DownloadProgress](apiServiceConfiguration.kafkaConfiguration)
          scheduledVideoDownloadPubSub <- KafkaPubSub[F, ScheduledVideoDownload](apiServiceConfiguration.kafkaConfiguration)
          healthCheckPubSub <- KafkaPubSub[F, HealthCheckMessage](apiServiceConfiguration.kafkaConfiguration)

          schedulingService = new SchedulingServiceImpl[F, ConnectionIO](
            videoAnalysisService,
            DoobieSchedulingDao,
            downloadProgressPubSub,
            scheduledVideoDownloadPubSub
          )

          healthService = new HealthServiceImpl[F](
            repositoryService,
            healthCheckKeyStore,
            healthCheckPubSub,
            apiServiceConfiguration.applicationInformation,
            apiServiceConfiguration.downloadConfiguration
          )

          authenticationService =
            apiServiceConfiguration.authenticationConfiguration match {
              case AuthenticationConfiguration.NoAuthenticationConfiguration =>
                new NoAuthenticationService[F]

              case passwordAuthenticationConfiguration: PasswordAuthenticationConfiguration =>
                new AuthenticationServiceImpl[F](
                  authenticationKeyStore,
                  passwordAuthenticationConfiguration,
                  cpuBlocker
                )
            }

          backgroundService =
            new BackgroundServiceImpl[F](
              downloadProgressPubSub,
              schedulingService,
              s"background-${apiServiceConfiguration.applicationInformation.instanceId}"
            )

          _ <- Resource.liftF(Concurrent[F].start(backgroundService.run))

        } yield
          Routes(
            videoService,
            videoAnalysisService,
            schedulingService,
            assetService,
            healthService,
            authenticationService,
            ioBlocker,
            Logger[F, ApiApp.type]
          )
      }

}
