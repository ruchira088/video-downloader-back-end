package com.ruchij

import cats.effect.{Blocker, ExitCode, IO, IOApp}
import com.ruchij.config.BatchServiceConfiguration
import com.ruchij.daos.doobie.DoobieTransactor
import com.ruchij.daos.resource.DoobieFileResourceDao
import com.ruchij.daos.snapshot.DoobieSnapshotDao
import com.ruchij.migration.MigrationApp
import com.ruchij.services.enrichment.VideoEnrichmentServiceImpl
import com.ruchij.services.hashing.MurmurHash3Service
import com.ruchij.services.repository.FileRepositoryService
import com.ruchij.services.sync.SynchronizationServiceImpl
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

          repositoryService = new FileRepositoryService[IO](blocker)
          hashingService = new MurmurHash3Service[IO](blocker)

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
              videoEnrichmentService,
              hashingService,
              blocker,
              batchServiceConfiguration.downloadConfiguration
            )

          _ <- synchronizationService.sync

//          synchronizationService =
//            new SynchronizationServiceImpl[IO, repositoryService.BackedType]() {
//
//            }
//
//          _ <- schedulingDao.insert(scheduledVideoDownload)
//          _ <- videoDao.insert(videoId, videoFileResource)
//
//          Some(video) <- videoDao.findById(videoId)
//          _ <- IO.delay(println(video))
//
//          snapshots <- videoEnrichmentService.videoSnapshots(video)
//          _ <- IO.delay(println(snapshots))
        }
        yield ExitCode.Success

    }

}
