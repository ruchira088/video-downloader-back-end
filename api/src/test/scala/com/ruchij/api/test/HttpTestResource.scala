package com.ruchij.api.test

import cats.effect.Resource
import cats.effect.kernel.Async
import cats.effect.std.Dispatcher
import cats.{ApplicativeError, Id}
import com.comcast.ip4s.IpLiteralSyntax
import com.ruchij.api.ApiApp
import com.ruchij.api.config.{ApiServiceConfiguration, ApiStorageConfiguration, AuthenticationConfiguration, FallbackApiConfiguration, HttpConfiguration}
import com.ruchij.api.models.ApiMessageBrokers
import com.ruchij.api.services.health.models.messaging.HealthCheckMessage
import com.ruchij.core.commands.ScanVideosCommand
import com.ruchij.core.config.{KafkaConfiguration, SpaSiteRendererConfiguration}
import com.ruchij.core.daos.doobie.DoobieTransactor
import com.ruchij.core.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.core.external.ExternalServiceProvider
import com.ruchij.core.external.ExternalServiceProvider.migrationServiceConfiguration
import com.ruchij.core.kv.RedisKeyValueStore
import com.ruchij.core.messaging.inmemory.Fs2PubSub
import com.ruchij.core.messaging.models.HttpMetric
import com.ruchij.core.services.scheduling.models.{DownloadProgress, WorkerStatusUpdate}
import com.ruchij.core.types.JodaClock
import com.ruchij.migration.MigrationApp
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.effect.Log.Stdout.instance
import org.http4s.client.Client
import org.http4s.{HttpApp, Uri}

import scala.concurrent.duration._
import scala.language.postfixOps

object HttpTestResource {
  type TestResources[F[_]] = (ApiServiceConfiguration, ApiMessageBrokers[F, Id], HttpApp[F])

  val ApiStorageConfig: ApiStorageConfiguration = ApiStorageConfiguration("./images")

  val HttpConfig: HttpConfiguration = HttpConfiguration(ipv4"127.0.0.1", port"8000")

  val AuthenticationConfig: AuthenticationConfiguration =
    AuthenticationConfiguration(30 days)

  val KafkaConfig: KafkaConfiguration = KafkaConfiguration("N/A", Uri())

  val SpaRendererConfig: SpaSiteRendererConfiguration = SpaSiteRendererConfiguration(Uri())

  val FallbackApiConfig: FallbackApiConfiguration = FallbackApiConfiguration(Uri(), "")

  def create[F[_]: Async: JodaClock](
    externalServiceProvider: ExternalServiceProvider[F]
  ): Resource[F, TestResources[F]] =
    create[F](externalServiceProvider, Client[F] { _ =>
      Resource.eval {
        ApplicativeError[F, Throwable].raiseError(new NotImplementedError("Client has not been implemented"))
      }
    })

  def create[F[_]: Async: JodaClock](
    externalServiceProvider: ExternalServiceProvider[F],
    client: Client[F]
  ): Resource[F, TestResources[F]] =
    for {
      redisConfiguration <- externalServiceProvider.redisConfiguration
      redisCommands <- Redis[F].utf8(redisConfiguration.uri)
      redisKeyValueStore = new RedisKeyValueStore[F](redisCommands)

      databaseConfiguration <- externalServiceProvider.databaseConfiguration
      _ <- Resource.eval(MigrationApp.migration(migrationServiceConfiguration(databaseConfiguration)))
      hikariTransactor <- DoobieTransactor.create(databaseConfiguration)

      apiServiceConfiguration = ApiServiceConfiguration(
        HttpConfig,
        ApiStorageConfig,
        databaseConfiguration,
        redisConfiguration,
        AuthenticationConfig,
        KafkaConfig,
        SpaRendererConfig,
        FallbackApiConfig
      )

      downloadProgressPubSub <- Resource.eval(Fs2PubSub[F, DownloadProgress])
      scheduledVideoDownloadPubSub <- Resource.eval(Fs2PubSub[F, ScheduledVideoDownload])
      healthCheckPubSub <- Resource.eval(Fs2PubSub[F, HealthCheckMessage])
      httpMetricPubSub <- Resource.eval(Fs2PubSub[F, HttpMetric])
      workerStatusUpdatesPubSub <- Resource.eval(Fs2PubSub[F, WorkerStatusUpdate])
      scanVideoCommandPubSub <- Resource.eval(Fs2PubSub[F, ScanVideosCommand])
      dispatcher <- Dispatcher[F]

      messageBrokers = ApiMessageBrokers(
        downloadProgressPubSub,
        scheduledVideoDownloadPubSub,
        healthCheckPubSub,
        workerStatusUpdatesPubSub,
        scanVideoCommandPubSub,
        httpMetricPubSub
      )

      httpApp <- Resource.eval {
        ApiApp.program[F, Id](
          hikariTransactor,
          client,
          redisKeyValueStore,
          messageBrokers,
          dispatcher,
          apiServiceConfiguration
        )(Async[F], JodaClock[F])
      }
    } yield (apiServiceConfiguration, messageBrokers, httpApp)

}
