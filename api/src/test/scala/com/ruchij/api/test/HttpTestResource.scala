package com.ruchij.api.test

import cats.{ApplicativeError, Id}
import cats.effect.{Blocker, ConcurrentEffect, ContextShift, Resource, Timer}
import com.ruchij.api.ApiApp
import com.ruchij.api.config.AuthenticationConfiguration.{HashedPassword, PasswordAuthenticationConfiguration}
import com.ruchij.api.config.{ApiServiceConfiguration, ApiStorageConfiguration, HttpConfiguration}
import com.ruchij.api.models.ApiMessageBrokers
import com.ruchij.api.services.health.models.messaging.HealthCheckMessage
import com.ruchij.core.config.{ApplicationInformation, KafkaConfiguration}
import com.ruchij.core.daos.doobie.DoobieTransactor
import com.ruchij.core.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.core.kv.RedisKeyValueStore
import com.ruchij.core.messaging.inmemory.Fs2PubSub
import com.ruchij.core.messaging.models.HttpMetric
import com.ruchij.core.services.scheduling.models.{DownloadProgress, WorkerStatusUpdate}
import com.ruchij.core.test.{DoobieProvider, Resources}
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

  val PasswordAuthenticationConfig: PasswordAuthenticationConfiguration =
    PasswordAuthenticationConfiguration(
      HashedPassword("$2a$10$m5CQAirrrJKRqG3oalNSU.TUOn56v88isxMbNPi8cXXI35gY20hO."), // The password is "top-secret"
      30 days
    )

  val KafkaConfig: KafkaConfiguration = KafkaConfiguration("N/A", Uri())

  def create[F[+ _]: ConcurrentEffect: Timer: ContextShift](implicit executionContext: ExecutionContext): Resource[F, TestResources[F]] =
    create[F] {
      Client[F] { _ =>
        Resource.eval {
          ApplicativeError[F, Throwable].raiseError(new NotImplementedError("Client has not been implemented"))
        }
      }
    }

  def create[F[+ _]: ConcurrentEffect: Timer: ContextShift](client: Client[F])(implicit executionContext: ExecutionContext): Resource[F, TestResources[F]] =
    for {
      (redisConfiguration, _) <- Resources.startEmbeddedRedis[F]
      redisCommands <- Redis[F].utf8(redisConfiguration.uri)
      redisKeyValueStore = new RedisKeyValueStore[F](redisCommands)

//      (kafkaConfiguration, _) <- Resources.startEmbeddedKafkaAndSchemaRegistry[F]

      databaseConfiguration <- Resource.eval(DoobieProvider.uniqueInMemoryDbConfig[F])
      transactor <- DoobieTransactor.create(
        databaseConfiguration,
        executionContext,
        Blocker.liftExecutionContext(executionContext)
      )

      apiServiceConfiguration = ApiServiceConfiguration(
        HttpConfig,
        ApiStorageConfig,
        databaseConfiguration,
        redisConfiguration,
        PasswordAuthenticationConfig,
        KafkaConfig,
        ApplicationInfo
      )

      downloadProgressPubSub <- Resource.eval(Fs2PubSub[F, DownloadProgress])
      scheduledVideoDownloadPubSub <- Resource.eval(Fs2PubSub[F, ScheduledVideoDownload])
      healthCheckPubSub <- Resource.eval(Fs2PubSub[F, HealthCheckMessage])
      httpMetricPubSub <- Resource.eval(Fs2PubSub[F, HttpMetric])
      workerStatusUpdatesPubSub <- Resource.eval(Fs2PubSub[F, WorkerStatusUpdate])

      messageBrokers = ApiMessageBrokers(
        downloadProgressPubSub,
        scheduledVideoDownloadPubSub,
        healthCheckPubSub,
        workerStatusUpdatesPubSub,
        httpMetricPubSub
      )

      httpApp <-
        Resource.eval {
          ApiApp.program[F, Id](
            client,
            redisKeyValueStore,
            messageBrokers,
            Blocker.liftExecutionContext(executionContext),
            Blocker.liftExecutionContext(executionContext),
            apiServiceConfiguration
          )(ConcurrentEffect[F], ContextShift[F], Timer[F], transactor.trans)
        }
    } yield (apiServiceConfiguration, messageBrokers, httpApp)

}
