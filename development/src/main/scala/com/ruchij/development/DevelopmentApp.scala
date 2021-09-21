package com.ruchij.development

import cats.ApplicativeError
import cats.data.OptionT
import cats.effect.{Blocker, ConcurrentEffect, ContextShift, ExitCode, IO, IOApp, Resource, Sync, Timer}
import cats.implicits._
import com.ruchij.api.ApiApp
import com.ruchij.api.config.{ApiServiceConfiguration, ApiStorageConfiguration, AuthenticationConfiguration, HttpConfiguration}
import com.ruchij.batch.BatchApp
import com.ruchij.batch.config.{BatchServiceConfiguration, BatchStorageConfiguration, WorkerConfiguration}
import com.ruchij.batch.services.scheduler.Scheduler
import com.ruchij.core.config.{ApplicationInformation, KafkaConfiguration, RedisConfiguration}
import com.ruchij.core.exceptions.ResourceNotFoundException
import com.ruchij.core.test.Resources.{startEmbeddedKafkaAndSchemaRegistry, startEmbeddedRedis}
import com.ruchij.migration.MigrationApp
import com.ruchij.migration.config.{AdminConfiguration, DatabaseConfiguration, MigrationServiceConfiguration}
import org.http4s.HttpApp
import org.http4s.blaze.server.BlazeServerBuilder
import org.joda.time.LocalTime

import java.security.KeyStore
import javax.net.ssl.{KeyManagerFactory, SSLContext}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

object DevelopmentApp extends IOApp {
  val HashedAdminPassword = "$2a$10$m5CQAirrrJKRqG3oalNSU.TUOn56v88isxMbNPi8cXXI35gY20hO." // The password is "top-secret"

  val DatabaseConfig: DatabaseConfiguration =
    DatabaseConfiguration(
      "jdbc:h2:./video-downloader;MODE=PostgreSQL;DATABASE_TO_UPPER=false",
      "",
      ""
    )

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

  def apiConfig(redisConfiguration: RedisConfiguration, kafkaConfiguration: KafkaConfiguration): ApiServiceConfiguration =
    ApiServiceConfiguration(
      HttpConfig,
      ApiStorageConfig,
      DatabaseConfig,
      redisConfiguration,
      AuthenticationConfig,
      kafkaConfiguration,
      ApplicationInfo
    )

  def batchConfig(kafkaConfiguration: KafkaConfiguration): BatchServiceConfiguration =
    BatchServiceConfiguration(BatchStorageConfig, WorkerConfig, DatabaseConfig, kafkaConfiguration, ApplicationInfo)

  val KeyStoreResource = "/localhost.jks"

  val KeyStorePassword = "changeit"

  override def run(args: List[String]): IO[ExitCode] =
    program[IO].use {
      case (api, batch, sslContext) =>
        for {
          _ <- BlazeServerBuilder[IO](ExecutionContext.global)
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

  def program[F[+ _]: ConcurrentEffect: Timer: ContextShift]: Resource[F, (HttpApp[F], Scheduler[F], SSLContext)] =
    for {
      (redisConfig, _) <- startEmbeddedRedis[F]
      (kafkaConfig, _) <- startEmbeddedKafkaAndSchemaRegistry[F]
      blocker <- Blocker[F]
      sslContext <- Resource.eval(blocker.blockOn(createSslContext[F]))

      _ <-
        Resource.eval {
          MigrationApp.migration[F](
            MigrationServiceConfiguration(DatabaseConfig, AdminConfiguration(HashedAdminPassword)), blocker)
        }

      api <- ApiApp.create[F](apiConfig(redisConfig, kafkaConfig))
      batch <- BatchApp.program[F](batchConfig(kafkaConfig))
    } yield (api, batch, sslContext)

  def createSslContext[F[_]: Sync]: F[SSLContext] =
    for {
      keyStore <- Sync[F].delay(KeyStore.getInstance("JKS"))

      _ <- Resource
        .make {
          OptionT(Sync[F].delay(Option(getClass.getResourceAsStream(KeyStoreResource))))
            .getOrElseF {
              ApplicativeError[F, Throwable].raiseError {
                ResourceNotFoundException(s"Unable to find KeyStore at $KeyStoreResource")
              }
            }
        }(inputStream => Sync[F].delay(inputStream.close()))
        .use { inputStream =>
          Sync[F].delay(keyStore.load(inputStream, KeyStorePassword.toCharArray))
        }

      algorithm <- Sync[F].delay(KeyManagerFactory.getDefaultAlgorithm)
      keyManagerFactory <- Sync[F].delay(KeyManagerFactory.getInstance(algorithm))
      _ <- Sync[F].delay(keyManagerFactory.init(keyStore, KeyStorePassword.toCharArray))

      sslContext <- Sync[F].delay(SSLContext.getInstance("TLS"))
      _ <- Sync[F].delay(sslContext.init(keyManagerFactory.getKeyManagers, null, null))
    } yield sslContext

}
