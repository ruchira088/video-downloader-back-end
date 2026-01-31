package com.ruchij.development

import cats.ApplicativeError
import cats.data.OptionT
import cats.effect._
import cats.implicits._
import com.comcast.ip4s.IpLiteralSyntax
import com.ruchij.api.ApiApp
import com.ruchij.api.config._
import com.ruchij.api.external.ApiResourcesProvider
import com.ruchij.api.external.containers.ContainerApiResourcesProvider
import com.ruchij.batch.BatchApp
import com.ruchij.batch.config.{BatchServiceConfiguration, WorkerConfiguration}
import com.ruchij.batch.services.scheduler.Scheduler
import com.ruchij.core.config.{KafkaConfiguration, RedisConfiguration, SentryConfiguration, SpaSiteRendererConfiguration, StorageConfiguration}
import com.ruchij.core.exceptions.ResourceNotFoundException
import com.ruchij.core.external.CoreResourcesProvider.HashedAdminPassword
import com.ruchij.core.logging.Logger
import com.ruchij.core.types.JodaClock
import com.ruchij.development.frontend.FrontEndContainer
import com.ruchij.migration.MigrationApp
import com.ruchij.migration.config.{AdminConfiguration, DatabaseConfiguration, MigrationServiceConfiguration}
import fs2.compression.Compression
import fs2.io.file.Files
import fs2.io.net.tls.TLSContext
import org.http4s.HttpApp
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.http4sLiteralsSyntax
import org.joda.time.LocalTime

import java.security.KeyStore
import javax.net.ssl.{KeyManagerFactory, SSLContext}
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

object DevelopmentApp extends IOApp {
  private val StorageConfig: StorageConfiguration =
    StorageConfiguration(
      "/Users/ruchira/Development/video-downloader-back-end/videos",
      "/Users/ruchira/Development/video-downloader-back-end/images",
      List.empty
    )

  private val WorkerConfig: WorkerConfiguration =
    WorkerConfiguration(2, LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, "development-app")

  private val HttpConfig: HttpConfiguration =
    HttpConfiguration(
      ipv4"0.0.0.0",
      port"443",
      Some(
        Set("*.localhost", "*.ruchij.com", "192.168.*.*", "10.*.*.*", "172.*.*.*")
      )
    )

  private val AuthenticationConfig: AuthenticationConfiguration =
    AuthenticationConfiguration(30 days)

  private val SentryConfig: SentryConfiguration =
    SentryConfiguration(None, "development", 1.0)

  private def apiConfig(
    databaseConfiguration: DatabaseConfiguration,
    redisConfiguration: RedisConfiguration,
    kafkaConfiguration: KafkaConfiguration,
    spaSiteRendererConfiguration: SpaSiteRendererConfiguration,
  ): ApiServiceConfiguration =
    ApiServiceConfiguration(
      HttpConfig,
      StorageConfig,
      databaseConfiguration,
      redisConfiguration,
      AuthenticationConfig,
      kafkaConfiguration,
      spaSiteRendererConfiguration,
      SentryConfig
    )

  private def batchConfig(
    databaseConfiguration: DatabaseConfiguration,
    redisConfiguration: RedisConfiguration,
    kafkaConfiguration: KafkaConfiguration,
    spaSiteRendererConfiguration: SpaSiteRendererConfiguration
  ): BatchServiceConfiguration =
    BatchServiceConfiguration(
      StorageConfig,
      WorkerConfig,
      databaseConfiguration,
      kafkaConfiguration,
      redisConfiguration,
      spaSiteRendererConfiguration,
      SentryConfig
    )

  private val KeyStoreResource = "/localhost.jks"

  private val KeyStorePassword = "changeit"

  private val logger = Logger[DevelopmentApp.type]

  override def run(args: List[String]): IO[ExitCode] =
    program[IO](new ContainerApiResourcesProvider[IO])
      .flatMap {
        case (api, batch) =>
          Resource
            .eval(createSslContext[IO].map(TLSContext.Builder.forAsync[IO].fromSSLContext))
            .flatMap { tlsContext =>
              EmberServerBuilder
                .default[IO]
                .withHttpApp(api)
                .withHost(HttpConfig.host)
                .withPort(HttpConfig.port)
                .withTLS(tlsContext)
                .build
            }
            .productR(FrontEndContainer.create[IO](uri"https://api.localhost"))
            .evalMap(frontEndUri => logger.info[IO](s"***** FrontEnd URL: ${frontEndUri.renderString} *****"))
            .as(batch)
      }
      .use { batch =>
        for {
          _ <- batch.init
          _ <- batch.run.compile.drain
        } yield ExitCode.Success
      }

  private def program[F[_]: Async: JodaClock: Files: Compression](
    externalApiServiceProvider: ApiResourcesProvider[F]
  ): Resource[F, (HttpApp[F], Scheduler[F])] =
    for {
      redisConfig <- externalApiServiceProvider.redisConfiguration
      kafkaConfig <- externalApiServiceProvider.kafkaConfiguration
      databaseConfig <- externalApiServiceProvider.databaseConfiguration
      spaSiteRendererConfig <- externalApiServiceProvider.spaSiteRendererConfiguration
      _ <- Resource.eval {
        logger.info[F] {
          s"""JDBC_URL=${databaseConfig.url}, USERNAME="${databaseConfig.user}", PASSWORD="${databaseConfig.password}""""
        }
      }

      _ <- Resource.eval {
        MigrationApp.migration[F](
          MigrationServiceConfiguration(databaseConfig, AdminConfiguration(HashedAdminPassword))
        )
      }

      api <- ApiApp.create[F](apiConfig(databaseConfig, redisConfig, kafkaConfig, spaSiteRendererConfig))
      batch <- BatchApp.program[F](batchConfig(databaseConfig, redisConfig, kafkaConfig, spaSiteRendererConfig))
    } yield (api, batch)

  private def createSslContext[F[_]: Sync]: F[SSLContext] =
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
