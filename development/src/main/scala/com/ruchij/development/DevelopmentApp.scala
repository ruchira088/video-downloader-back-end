package com.ruchij.development

import java.security.KeyStore

import cats.effect.{Blocker, ConcurrentEffect, ContextShift, ExitCode, IO, IOApp, Resource, Sync, Timer}
import cats.implicits._
import com.ruchij.api.ApiApp
import com.ruchij.api.config.AuthenticationConfiguration.HashedPassword
import com.ruchij.api.config.{ApiServiceConfiguration, AuthenticationConfiguration, HttpConfiguration}
import com.ruchij.batch.BatchApp
import com.ruchij.batch.config.{BatchServiceConfiguration, WorkerConfiguration}
import com.ruchij.batch.services.scheduler.Scheduler
import com.ruchij.core.config.{ApplicationInformation, DownloadConfiguration, RedisConfiguration}
import com.ruchij.migration.MigrationApp
import com.ruchij.migration.config.DatabaseConfiguration
import javax.net.ssl.{KeyManagerFactory, SSLContext}
import org.http4s.HttpApp
import org.http4s.server.blaze.BlazeServerBuilder
import org.joda.time.LocalTime
import redis.embedded.RedisServer

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

object DevelopmentApp extends IOApp {
  val DatabaseConfig: DatabaseConfiguration =
    DatabaseConfiguration(
      "jdbc:h2:./video-downloader;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
      "",
      ""
    )

  val DownloadConfig: DownloadConfiguration =
    DownloadConfiguration("./videos", "./images")

  val RedisConfig: RedisConfiguration =
    RedisConfiguration("localhost", 6300, None)

  val ApplicationInfo: ApplicationInformation =
    ApplicationInformation(Some("N/A"), Some("N/A"), None)

  val WorkerConfig: WorkerConfiguration =
    WorkerConfiguration(2, LocalTime.MIDNIGHT, LocalTime.MIDNIGHT)

  val HttpConfig: HttpConfiguration = HttpConfiguration("0.0.0.0", 443)

  val AuthenticationConfig: AuthenticationConfiguration =
    AuthenticationConfiguration(
      HashedPassword("$2a$10$m5CQAirrrJKRqG3oalNSU.TUOn56v88isxMbNPi8cXXI35gY20hO."), // The password is "top-secret"
      30 days
    )

  val ApiConfig: ApiServiceConfiguration =
    ApiServiceConfiguration(
      HttpConfig,
      DownloadConfig,
      DatabaseConfig,
      RedisConfig,
      AuthenticationConfig,
      ApplicationInfo
    )

  val BatchConfig: BatchServiceConfiguration =
    BatchServiceConfiguration(DownloadConfig, WorkerConfig, RedisConfig, DatabaseConfig)

  val KeyStorePassword = "changeit"

  override def run(args: List[String]): IO[ExitCode] =
    program[IO].use {
      case (api, batch, sslContext) =>
        for {
          _ <-
            BlazeServerBuilder[IO](ExecutionContext.global)
              .withHttpApp(api)
              .bindHttp(HttpConfig.port, HttpConfig.host)
              .withSslContext(sslContext)
              .serve
              .compile
              .drain
              .start

          _ <- batch.init
          _ <- batch.run.compile.drain
        }
        yield ExitCode.Success
    }

  def program[F[+ _]: ConcurrentEffect: Timer: ContextShift]: Resource[F, (HttpApp[F], Scheduler[F], SSLContext)] =
    for {
      _ <- startEmbeddedRedis[F]
      blocker <- Blocker[F]
      sslContext <- Resource.liftF(blocker.blockOn(createSslContext[F]))

      _ <- Resource.liftF(MigrationApp.migration[F](DatabaseConfig, blocker))

      api <- ApiApp.program[F](ApiConfig, ExecutionContext.global)
      batch <- BatchApp.program[F](BatchConfig, ExecutionContext.global)
    } yield (api, batch, sslContext)

  def startEmbeddedRedis[F[_]: Sync]: Resource[F, RedisServer] =
    Resource
      .pure[F, RedisServer](RedisServer.builder().port(RedisConfig.port).build())
      .flatTap { redisServer =>
        Resource.make(Sync[F].delay(redisServer.start()))(_ => Sync[F].delay(redisServer.stop()))
      }

  def createSslContext[F[_]: Sync]: F[SSLContext] =
    for {
      keyStore <- Sync[F].delay(KeyStore.getInstance("JKS"))
      keyInputStream <- Sync[F].delay(getClass.getResourceAsStream("/localhost.jks"))
      _ = keyStore.load(keyInputStream, KeyStorePassword.toCharArray)
      _ <- Sync[F].delay(keyInputStream.close())

      algorithm <- Sync[F].delay(KeyManagerFactory.getDefaultAlgorithm)
      keyManagerFactory <- Sync[F].delay(KeyManagerFactory.getInstance(algorithm))
      _ = keyManagerFactory.init(keyStore, KeyStorePassword.toCharArray)

      sslContext <- Sync[F].delay(SSLContext.getInstance("TLS"))
      _ = sslContext.init(keyManagerFactory.getKeyManagers, null, null)
    }
    yield sslContext

}
