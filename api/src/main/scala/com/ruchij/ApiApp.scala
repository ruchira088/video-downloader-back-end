package com.ruchij

import java.util.concurrent.Executors

import cats.effect.{Blocker, ConcurrentEffect, ContextShift, ExitCode, IO, IOApp, Resource, Sync, Timer}
import com.ruchij.config.WebServiceConfiguration
import com.ruchij.daos.doobie.DoobieTransactor
import com.ruchij.daos.resource.DoobieFileResourceDao
import com.ruchij.daos.scheduling.DoobieSchedulingDao
import com.ruchij.daos.snapshot.DoobieSnapshotDao
import com.ruchij.daos.video.DoobieVideoDao
import com.ruchij.daos.videometadata.DoobieVideoMetadataDao
import com.ruchij.migration.MigrationApp
import com.ruchij.services.asset.AssetServiceImpl
import com.ruchij.services.download.Http4sDownloadService
import com.ruchij.services.hashing.MurmurHash3Service
import com.ruchij.services.health.HealthServiceImpl
import com.ruchij.services.repository.FileRepositoryService
import com.ruchij.services.scheduling.SchedulingServiceImpl
import com.ruchij.services.video.{VideoAnalysisServiceImpl, VideoServiceImpl}
import com.ruchij.types.FunctionKTypes
import com.ruchij.web.Routes
import doobie.free.connection.ConnectionIO
import org.http4s.HttpApp
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.client.middleware.FollowRedirect
import org.http4s.server.blaze.BlazeServerBuilder
import pureconfig.ConfigSource

import scala.concurrent.ExecutionContext

object ApiApp extends IOApp {
  override def run(args: List[String]): IO[ExitCode] =
    for {
      configObjectSource <- IO.delay(ConfigSource.defaultApplication)
      webServiceConfiguration <- WebServiceConfiguration.parse[IO](configObjectSource)

      _ <- program[IO](webServiceConfiguration, ExecutionContext.global)
        .use { httpApp =>
          BlazeServerBuilder
            .apply[IO](ExecutionContext.global)
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
    Resource
      .liftF(DoobieTransactor.create[F](serviceConfiguration.databaseConfiguration))
      .map(FunctionKTypes.transaction[F])
      .flatMap { implicit transaction =>
        for {
          baseClient <- BlazeClientBuilder[F](executionContext).resource
          httpClient = FollowRedirect(maxRedirects = 10)(baseClient)

          ioThreadPool <- Resource.liftF(Sync[F].delay(Executors.newCachedThreadPool()))
          ioBlocker = Blocker.liftExecutionContext(ExecutionContext.fromExecutor(ioThreadPool))

          processorCount <- Resource.liftF(Sync[F].delay(Runtime.getRuntime.availableProcessors()))
          cpuBlockingThreadPool <- Resource.liftF(Sync[F].delay(Executors.newFixedThreadPool(processorCount)))
          cpuBlocker = Blocker.liftExecutionContext(ExecutionContext.fromExecutor(cpuBlockingThreadPool))

          _ <- Resource.liftF(MigrationApp.migration[F](serviceConfiguration.databaseConfiguration, ioBlocker))

          hashingService = new MurmurHash3Service[F](cpuBlocker)
          videoAnalysisService = new VideoAnalysisServiceImpl[F](httpClient)
          repositoryService = new FileRepositoryService[F](ioBlocker)
          videoService = new VideoServiceImpl[F, ConnectionIO](
            DoobieVideoDao,
            DoobieVideoMetadataDao,
            DoobieSnapshotDao,
            DoobieFileResourceDao
          )
          downloadService = new Http4sDownloadService[F](httpClient, repositoryService)
          assetService = new AssetServiceImpl[F, ConnectionIO](DoobieFileResourceDao, repositoryService)
          schedulingService = new SchedulingServiceImpl[F, ConnectionIO](
            videoAnalysisService,
            DoobieSchedulingDao,
            DoobieVideoMetadataDao,
            DoobieFileResourceDao,
            hashingService,
            downloadService,
            serviceConfiguration.downloadConfiguration
          )
          healthService = new HealthServiceImpl[F](
            serviceConfiguration.applicationInformation,
            repositoryService,
            serviceConfiguration.downloadConfiguration
          )

        } yield Routes(videoService, videoAnalysisService, schedulingService, assetService, healthService, ioBlocker)
      }

}
