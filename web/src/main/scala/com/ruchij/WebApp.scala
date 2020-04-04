package com.ruchij

import cats.effect.{Blocker, ConcurrentEffect, ContextShift, ExitCode, IO, IOApp, Resource, Timer}
import com.ruchij.config.WebServiceConfiguration
import com.ruchij.daos.doobie.DoobieTransactor
import com.ruchij.daos.scheduling.DoobieSchedulingDao
import com.ruchij.daos.videometadata.DoobieVideoMetadataDao
import com.ruchij.migration.MigrationApp
import com.ruchij.services.health.HealthServiceImpl
import com.ruchij.services.scheduling.SchedulingServiceImpl
import com.ruchij.services.video.VideoAnalysisServiceImpl
import com.ruchij.types.FunctionKTypes.eitherToF
import com.ruchij.web.Routes
import org.http4s.HttpApp
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.server.blaze.BlazeServerBuilder
import pureconfig.ConfigSource

import scala.concurrent.ExecutionContext

object WebApp extends IOApp {
  override def run(args: List[String]): IO[ExitCode] =
    for {
      configObjectSource <- IO.delay(ConfigSource.defaultApplication)
      webServiceConfiguration <- IO.suspend(WebServiceConfiguration.parse[IO](configObjectSource))

      _ <- program[IO](webServiceConfiguration, ExecutionContext.global)
        .use { httpApp =>
          BlazeServerBuilder[IO]
            .withHttpApp(httpApp)
            .bindHttp(webServiceConfiguration.httpConfiguration.port, webServiceConfiguration.httpConfiguration.host)
            .serve
            .compile
            .drain
        }
    } yield ExitCode.Success

  def program[F[_]: ConcurrentEffect: Timer: ContextShift](
    serviceConfiguration: WebServiceConfiguration,
    executionContext: ExecutionContext
  ): Resource[F, HttpApp[F]] =
    for {
      client <- BlazeClientBuilder[F](executionContext).resource
      blocker <- Blocker[F]

      _ <- Resource.liftF {
        MigrationApp.migration[F](serviceConfiguration.databaseConfiguration, blocker)
      }

      transactor <- Resource.liftF {
        DoobieTransactor.create[F](serviceConfiguration.databaseConfiguration, blocker)
      }

      videoMetadataDao = new DoobieVideoMetadataDao[F](transactor)
      schedulingDao = new DoobieSchedulingDao[F](videoMetadataDao, transactor)

      videoService = new VideoAnalysisServiceImpl[F](client)
      schedulingService = new SchedulingServiceImpl[F](videoService, schedulingDao)
      healthService = new HealthServiceImpl[F]
    } yield Routes(schedulingService, healthService)
}
