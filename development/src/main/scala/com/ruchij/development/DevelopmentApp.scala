package com.ruchij.development

import cats.ApplicativeError
import cats.data.OptionT
import cats.effect._
import cats.implicits._
import com.comcast.ip4s.IpLiteralSyntax
import com.ruchij.api.ApiApp
import com.ruchij.api.config.{ApiServiceConfiguration, ApiStorageConfiguration, AuthenticationConfiguration, FallbackApiConfiguration, HttpConfiguration}
import com.ruchij.batch.BatchApp
import com.ruchij.batch.config.{BatchServiceConfiguration, BatchStorageConfiguration, WorkerConfiguration}
import com.ruchij.batch.services.scheduler.Scheduler
import com.ruchij.core.config.{KafkaConfiguration, RedisConfiguration, SpaSiteRendererConfiguration}
import com.ruchij.core.exceptions.ResourceNotFoundException
import com.ruchij.core.external.ExternalServiceProvider
import com.ruchij.core.external.ExternalServiceProvider.HashedAdminPassword
import com.ruchij.core.external.embedded.EmbeddedExternalServiceProvider
import com.ruchij.core.types.JodaClock
import com.ruchij.migration.MigrationApp
import com.ruchij.migration.config.{AdminConfiguration, DatabaseConfiguration, MigrationServiceConfiguration}
import fs2.io.net.tls.TLSContext
import org.http4s.HttpApp
import org.http4s.ember.server.EmberServerBuilder
import org.joda.time.LocalTime

import java.security.KeyStore
import javax.net.ssl.{KeyManagerFactory, SSLContext}
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

object DevelopmentApp extends IOApp {

  val ApiStorageConfig: ApiStorageConfiguration = ApiStorageConfiguration("./images")

  val BatchStorageConfig: BatchStorageConfiguration =
    BatchStorageConfiguration("./videos", "./images", List.empty)

  val WorkerConfig: WorkerConfiguration =
    WorkerConfiguration(2, LocalTime.MIDNIGHT, LocalTime.MIDNIGHT)

  val HttpConfig: HttpConfiguration = HttpConfiguration(ipv4"0.0.0.0", port"443")

  val AuthenticationConfig: AuthenticationConfiguration =
    AuthenticationConfiguration(30 days)

  def apiConfig(
    databaseConfiguration: DatabaseConfiguration,
    redisConfiguration: RedisConfiguration,
    kafkaConfiguration: KafkaConfiguration,
    spaSiteRendererConfiguration: SpaSiteRendererConfiguration,
    fallbackApiConfiguration: FallbackApiConfiguration
  ): ApiServiceConfiguration =
    ApiServiceConfiguration(
      HttpConfig,
      ApiStorageConfig,
      databaseConfiguration,
      redisConfiguration,
      AuthenticationConfig,
      kafkaConfiguration,
      spaSiteRendererConfiguration,
      fallbackApiConfiguration
    )

  def batchConfig(
    databaseConfiguration: DatabaseConfiguration,
    kafkaConfiguration: KafkaConfiguration,
    spaSiteRendererConfiguration: SpaSiteRendererConfiguration
  ): BatchServiceConfiguration =
    BatchServiceConfiguration(
      BatchStorageConfig,
      WorkerConfig,
      databaseConfiguration,
      kafkaConfiguration,
      spaSiteRendererConfiguration
    )

  val KeyStoreResource = "/localhost.jks"

  val KeyStorePassword = "changeit"

  override def run(args: List[String]): IO[ExitCode] =
    program[IO](new EmbeddedExternalServiceProvider[IO])
      .flatMap {
        case (api, batch) =>
          Resource.eval(createSslContext[IO].map(TLSContext.Builder.forAsync[IO].fromSSLContext))
            .flatMap { tlsContext =>
              EmberServerBuilder
                .default[IO]
                .withHttpApp(api)
                .withHost(HttpConfig.host)
                .withPort(HttpConfig.port)
                .withTLS(tlsContext)
                .build
            }
          .as(batch)
      }
      .use {
      batch =>
        for {
          _ <- batch.init
          _ <- batch.run.compile.drain
        } yield ExitCode.Success
    }

  def program[F[_]: Async: JodaClock](
    externalServiceProvider: ExternalServiceProvider[F]
  ): Resource[F, (HttpApp[F], Scheduler[F])] =
    for {
      redisConfig <- externalServiceProvider.redisConfiguration
      kafkaConfig <- externalServiceProvider.kafkaConfiguration
      databaseConfig <- externalServiceProvider.databaseConfiguration
      spaSiteRendererConfig <- externalServiceProvider.spaSiteRendererConfiguration

      _ <- Resource.eval {
        MigrationApp.migration[F](
          MigrationServiceConfiguration(databaseConfig, AdminConfiguration(HashedAdminPassword))
        )
      }

      api <- ApiApp.create[F](apiConfig(databaseConfig, redisConfig, kafkaConfig, spaSiteRendererConfig, ???))
      batch <- BatchApp.program[F](batchConfig(databaseConfig, kafkaConfig, spaSiteRendererConfig))
    } yield (api, batch)

  def createSslContext[F[_]: Sync]: F[SSLContext] =
    for {
      keyStore <- Sync[F].delay(KeyStore.getInstance("JKS"))

      _ <- Resource
        .make {
          OptionT(Sync[F].blocking(Option(getClass.getResourceAsStream(KeyStoreResource))))
            .getOrElseF {
              ApplicativeError[F, Throwable].raiseError {
                ResourceNotFoundException(s"Unable to find KeyStore at $KeyStoreResource")
              }
            }
        }(inputStream => Sync[F].blocking(inputStream.close()))
        .use { inputStream =>
          Sync[F].blocking(keyStore.load(inputStream, KeyStorePassword.toCharArray))
        }

      algorithm <- Sync[F].delay(KeyManagerFactory.getDefaultAlgorithm)
      keyManagerFactory <- Sync[F].delay(KeyManagerFactory.getInstance(algorithm))
      _ <- Sync[F].delay(keyManagerFactory.init(keyStore, KeyStorePassword.toCharArray))

      sslContext <- Sync[F].delay(SSLContext.getInstance("TLS"))
      _ <- Sync[F].delay(sslContext.init(keyManagerFactory.getKeyManagers, null, null))
    } yield sslContext

}
