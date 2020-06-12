package com.ruchij

import cats.effect.{Blocker, ExitCode, IO, IOApp}
import com.ruchij.config.BatchServiceConfiguration
import com.ruchij.daos.doobie.DoobieTransactor
import com.ruchij.daos.resource.DoobieFileResourceDao
import com.ruchij.daos.snapshot.DoobieSnapshotDao
import com.ruchij.daos.video.DoobieVideoDao
import com.ruchij.daos.videometadata.DoobieVideoMetadataDao
import com.ruchij.migration.MigrationApp
import com.ruchij.services.enrichment.VideoEnrichmentServiceImpl
import com.ruchij.services.hashing.MurmurHash3Service
import com.ruchij.services.repository.FileRepositoryService
import com.ruchij.services.sync.SynchronizationServiceImpl
import com.ruchij.services.video.VideoServiceImpl
import pureconfig.ConfigSource

object BatchSandbox extends IOApp {
  override def run(args: List[String]): IO[ExitCode] =
    Blocker[IO].use {
      blocker =>
        for {
          configSource <- IO.delay(ConfigSource.defaultApplication)
          batchServiceConfiguration <- BatchServiceConfiguration.parse[IO](configSource)

          migrationResult <- MigrationApp.migration[IO](batchServiceConfiguration.databaseConfiguration, blocker)
          _ <- IO.delay(println(s"Migration result: $migrationResult"))

          transactor <- DoobieTransactor.create[IO](batchServiceConfiguration.databaseConfiguration)
          fileResourceDao = new DoobieFileResourceDao[IO](transactor)
          snapshotDao = new DoobieSnapshotDao[IO](fileResourceDao, transactor)
          videoMetadataDao = new DoobieVideoMetadataDao[IO](fileResourceDao, transactor)
          videoDao = new DoobieVideoDao[IO](fileResourceDao, transactor)

          repositoryService = new FileRepositoryService[IO](blocker)
          hashingService = new MurmurHash3Service[IO](blocker)
          videoService = new VideoServiceImpl[IO](videoDao)

          videoEnrichmentService =
            new VideoEnrichmentServiceImpl[IO, repositoryService.BackedType](
              repositoryService,
              snapshotDao,
              blocker,
              batchServiceConfiguration.downloadConfiguration
            )

          synchronizationService =
            new SynchronizationServiceImpl[IO, repositoryService.BackedType](
              repositoryService,
              fileResourceDao,
              videoMetadataDao,
              videoService,
              videoEnrichmentService,
              hashingService,
              blocker,
              batchServiceConfiguration.downloadConfiguration
            )

          syncResult <- synchronizationService.sync
        }
        yield ExitCode.Success

    }

}
