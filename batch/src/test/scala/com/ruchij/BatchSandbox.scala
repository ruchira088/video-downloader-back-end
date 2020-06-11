package com.ruchij

import java.util.concurrent.TimeUnit

import cats.effect.{Blocker, Clock, ExitCode, IO, IOApp}
import com.ruchij.config.BatchServiceConfiguration
import com.ruchij.daos.doobie.DoobieTransactor
import com.ruchij.daos.resource.DoobieFileResourceDao
import com.ruchij.daos.resource.models.FileResource
import com.ruchij.daos.scheduling.DoobieSchedulingDao
import com.ruchij.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.daos.snapshot.DoobieSnapshotDao
import com.ruchij.daos.video.DoobieVideoDao
import com.ruchij.daos.videometadata.DoobieVideoMetadataDao
import com.ruchij.daos.videometadata.models.{VideoMetadata, VideoSite}
import com.ruchij.migration.MigrationApp
import com.ruchij.services.enrichment.VideoEnrichmentServiceImpl
import com.ruchij.services.repository.FileRepositoryService
import org.http4s.MediaType
import org.http4s.implicits._
import org.joda.time.DateTime
import pureconfig.ConfigSource

import scala.concurrent.duration.Duration

object BatchSandbox extends IOApp {
  override def run(args: List[String]): IO[ExitCode] =
    Blocker[IO].use {
      blocker =>
        for {
          configSource <- IO.delay(ConfigSource.defaultApplication)
          batchServiceConfiguration <- BatchServiceConfiguration.parse[IO](configSource)

          migrationResult <- MigrationApp.migration[IO](batchServiceConfiguration.databaseConfiguration, blocker)
          _ <- IO.delay(println(s"Migration result: $migrationResult"))
          timestamp <- Clock[IO].realTime(TimeUnit.MILLISECONDS).map(timestamp => new DateTime(timestamp))

          imagePath = "/Users/ruchira/Development/video-downloader-api/images/1589467373835-b73.jpg"
          thumbnail = FileResource("my-image", timestamp, imagePath, MediaType.image.png, 10)

          videoPath = "/Users/ruchira/Development/video-downloader-api/videos/276892618_1920x1080_4000k.mp4"
          videoFileResource = FileResource("my-video", timestamp, videoPath, MediaType.video.mp4, 10_000)
          videoId = "sample-video"

          videoMetadata =
            VideoMetadata(
              uri"https://google.com",
              videoId,
              VideoSite.VPorn,
              "Sample Title",
              Duration(35, TimeUnit.MINUTES),
              10_000,
              thumbnail
            )

          scheduledVideoDownload = ScheduledVideoDownload(timestamp, timestamp, None, videoMetadata, 0, None)

          transactor <- DoobieTransactor.create[IO](batchServiceConfiguration.databaseConfiguration)
          fileResourceDao = new DoobieFileResourceDao[IO](transactor)
          snapshotDao = new DoobieSnapshotDao[IO](fileResourceDao, transactor)
          videoDao = new DoobieVideoDao[IO](fileResourceDao, transactor)
          videoMetadataDao = new DoobieVideoMetadataDao[IO](fileResourceDao)
          schedulingDao = new DoobieSchedulingDao[IO](videoMetadataDao, transactor)
          repositoryService = new FileRepositoryService[IO](blocker)

          videoEnrichmentService =
            new VideoEnrichmentServiceImpl[IO, repositoryService.BackedType](
              repositoryService,
              snapshotDao,
              blocker,
              batchServiceConfiguration.downloadConfiguration
            )

          _ <- schedulingDao.insert(scheduledVideoDownload)
          _ <- videoDao.insert(videoId, videoFileResource)

          Some(video) <- videoDao.findById(videoId)
          _ <- IO.delay(println(video))

          snapshots <- videoEnrichmentService.videoSnapshots(video)
          _ <- IO.delay(println(snapshots))
        }
        yield ExitCode.Success

    }

}
