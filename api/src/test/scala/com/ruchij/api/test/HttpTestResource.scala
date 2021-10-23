package com.ruchij.api.test

import cats.effect.Resource
import cats.effect.kernel.Async
import cats.effect.std.Dispatcher
import cats.{ApplicativeError, Id}
import com.ruchij.api.ApiApp
import com.ruchij.api.config.{ApiServiceConfiguration, ApiStorageConfiguration, AuthenticationConfiguration, HttpConfiguration}
import com.ruchij.api.models.ApiMessageBrokers
import com.ruchij.api.services.health.models.messaging.HealthCheckMessage
import com.ruchij.core.config.{ApplicationInformation, KafkaConfiguration}
import com.ruchij.core.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.core.kv.RedisKeyValueStore
import com.ruchij.core.messaging.inmemory.Fs2PubSub
import com.ruchij.core.messaging.models.HttpMetric
import com.ruchij.core.services.scheduling.models.{DownloadProgress, WorkerStatusUpdate}
import com.ruchij.core.external.ExternalServiceProvider
import com.ruchij.core.types.JodaClock
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.effect.Log.Stdout.instance
import org.http4s.client.Client
import org.http4s.{HttpApp, Uri}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.language.postfixOps

object HttpTestResource {
  type TestResources[F[_]] = (ApiServiceConfiguration, ApiMessageBrokers[F, Id], HttpApp[F])

  val ApiStorageConfig: ApiStorageConfiguration = ApiStorageConfiguration("./images")

  val ApplicationInfo: ApplicationInformation =
    ApplicationInformation("localhost", Some("N/A"), Some("N/A"), None)

  val HttpConfig: HttpConfiguration = HttpConfiguration("localhost", 8000)

  val AuthenticationConfig: AuthenticationConfiguration =
    AuthenticationConfiguration(30 days)

  val KafkaConfig: KafkaConfiguration = KafkaConfiguration("N/A", Uri())

  def create[F[+ _]: Async: JodaClock](
    externalServiceProvider: ExternalServiceProvider[F]
  )(implicit executionContext: ExecutionContext): Resource[F, TestResources[F]] =
    create[F](externalServiceProvider, Client[F] { _ =>
      Resource.eval {
        ApplicativeError[F, Throwable].raiseError(new NotImplementedError("Client has not been implemented"))
      }
    })

  def create[F[+ _]: Async: JodaClock](
    externalServiceProvider: ExternalServiceProvider[F],
    client: Client[F]
  )(implicit executionContext: ExecutionContext): Resource[F, TestResources[F]] =
    for {
      redisConfiguration <- externalServiceProvider.redisConfiguration
      redisCommands <- Redis[F].utf8(redisConfiguration.uri)
      redisKeyValueStore = new RedisKeyValueStore[F](redisCommands)

      databaseConfiguration <- externalServiceProvider.databaseConfiguration
      transactor <- ExternalServiceProvider.transactor(databaseConfiguration)

      apiServiceConfiguration = ApiServiceConfiguration(
        HttpConfig,
        ApiStorageConfig,
        databaseConfiguration,
        redisConfiguration,
        AuthenticationConfig,
        KafkaConfig,
        ApplicationInfo
      )

      downloadProgressPubSub <- Resource.eval(Fs2PubSub[F, DownloadProgress])
      scheduledVideoDownloadPubSub <- Resource.eval(Fs2PubSub[F, ScheduledVideoDownload])
      healthCheckPubSub <- Resource.eval(Fs2PubSub[F, HealthCheckMessage])
      httpMetricPubSub <- Resource.eval(Fs2PubSub[F, HttpMetric])
      workerStatusUpdatesPubSub <- Resource.eval(Fs2PubSub[F, WorkerStatusUpdate])
      dispatcher <- Dispatcher[F]

      messageBrokers = ApiMessageBrokers(
        downloadProgressPubSub,
        scheduledVideoDownloadPubSub,
        healthCheckPubSub,
        workerStatusUpdatesPubSub,
        httpMetricPubSub
      )

      httpApp <- Resource.eval {
        ApiApp.program[F, Id](
          client,
          redisKeyValueStore,
          messageBrokers,
          dispatcher,
          apiServiceConfiguration
        )(Async[F], JodaClock[F], transactor)
      }
    } yield (apiServiceConfiguration, messageBrokers, httpApp)

}
