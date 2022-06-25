package com.ruchij.development

import cats.ApplicativeError
import cats.data.OptionT
import cats.effect._
import cats.implicits._
import com.ruchij.api.ApiApp
import com.ruchij.api.config.{ApiServiceConfiguration, ApiStorageConfiguration, AuthenticationConfiguration, HttpConfiguration}
import com.ruchij.batch.BatchApp
import com.ruchij.batch.config.{BatchServiceConfiguration, BatchStorageConfiguration, WorkerConfiguration}
import com.ruchij.batch.services.scheduler.Scheduler
import com.ruchij.core.config.{ApplicationInformation, KafkaConfiguration, RedisConfiguration, SpaSiteRendererConfiguration}
import com.ruchij.core.exceptions.ResourceNotFoundException
import com.ruchij.core.external.ExternalServiceProvider
import com.ruchij.core.external.ExternalServiceProvider.HashedAdminPassword
import com.ruchij.core.external.embedded.EmbeddedExternalServiceProvider
import com.ruchij.core.types.JodaClock
import com.ruchij.migration.MigrationApp
import com.ruchij.migration.config.{AdminConfiguration, DatabaseConfiguration, MigrationServiceConfiguration}
import org.http4s.HttpApp
import org.http4s.blaze.server.BlazeServerBuilder
import org.joda.time.LocalTime

import java.security.KeyStore
import javax.net.ssl.{KeyManagerFactory, SSLContext}
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

object DevelopmentApp extends IOApp {

  val ApiStorageConfig: ApiStorageConfiguration = ApiStorageConfiguration("./images")

  val BatchStorageConfig: BatchStorageConfiguration =
    BatchStorageConfiguration("./videos", "./images", List.empty)

  val ApplicationInfo: ApplicationInformation =
    ApplicationInformation("localhost", Some("N/A"), Some("N/A"), None)

  val WorkerConfig: WorkerConfiguration =
    WorkerConfiguration(2, LocalTime.MIDNIGHT, LocalTime.MIDNIGHT)

  val HttpConfig: HttpConfiguration = HttpConfiguration("0.0.0.0", 8443)

  val AuthenticationConfig: AuthenticationConfiguration =
    AuthenticationConfiguration(30 days)

  def apiConfig(
    databaseConfiguration: DatabaseConfiguration,
    redisConfiguration: RedisConfiguration,
    kafkaConfiguration: KafkaConfiguration,
    spaSiteRendererConfiguration: SpaSiteRendererConfiguration
  ): ApiServiceConfiguration =
    ApiServiceConfiguration(
      HttpConfig,
      ApiStorageConfig,
      databaseConfiguration,
      redisConfiguration,
      AuthenticationConfig,
      kafkaConfiguration,
      spaSiteRendererConfiguration,
      ApplicationInfo
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
      spaSiteRendererConfiguration,
      ApplicationInfo
    )

  val KeyStoreResource = "/localhost.jks"

  val KeyStorePassword = "changeit"

  override def run(args: List[String]): IO[ExitCode] =
    program[IO](new EmbeddedExternalServiceProvider[IO]).use {
      case (api, batch, sslContext) =>
        for {
          _ <- BlazeServerBuilder[IO]
            .withHttpApp(api)
            .withoutBanner
            .bindHttp(HttpConfig.port, HttpConfig.host)
            .withSslContext(sslContext)
            .serve
            .compile
            .drain
            .start

          _ <- batch.init
          _ <- batch.run.compile.drain
        } yield ExitCode.Success
    }

  def program[F[+ _]: Async: JodaClock](
    externalServiceProvider: ExternalServiceProvider[F]
  ): Resource[F, (HttpApp[F], Scheduler[F], SSLContext)] =
    for {
      redisConfig <- externalServiceProvider.redisConfiguration
      kafkaConfig <- externalServiceProvider.kafkaConfiguration
      databaseConfig <- externalServiceProvider.databaseConfiguration
      spaSiteRendererConfig <- externalServiceProvider.spaSiteRendererConfiguration

      sslContext <- Resource.eval(createSslContext[F])

      _ <- Resource.eval {
        MigrationApp.migration[F](
          MigrationServiceConfiguration(databaseConfig, AdminConfiguration(HashedAdminPassword))
        )
      }

      api <- ApiApp.create[F](apiConfig(databaseConfig, redisConfig, kafkaConfig, spaSiteRendererConfig))
      batch <- BatchApp.program[F](batchConfig(databaseConfig, kafkaConfig, spaSiteRendererConfig))
    } yield (api, batch, sslContext)

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
