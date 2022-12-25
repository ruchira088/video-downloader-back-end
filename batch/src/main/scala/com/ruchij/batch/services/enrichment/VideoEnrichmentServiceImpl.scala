package com.ruchij.batch.services.enrichment

import cats.effect.Sync
import cats.implicits._
import cats.{Monad, ~>}
import com.ruchij.batch.services.snapshots.VideoSnapshotService
import com.ruchij.core.config.StorageConfiguration
import com.ruchij.core.daos.resource.FileResourceDao
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.snapshot.SnapshotDao
import com.ruchij.core.daos.snapshot.models.Snapshot
import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.logging.Logger
import org.http4s.MediaType

import scala.concurrent.duration.FiniteDuration

class VideoEnrichmentServiceImpl[F[_]: Sync,T[_]: Monad](
  videoSnapshotService: VideoSnapshotService[F],
  snapshotDao: SnapshotDao[T],
  fileResourceDao: FileResourceDao[T],
  storageConfiguration: StorageConfiguration
)(implicit transaction: T ~> F)
    extends VideoEnrichmentService[F] {
  private val logger = Logger[VideoEnrichmentServiceImpl[F, T]]

  override val snapshotMediaType: MediaType = MediaType.image.jpeg

  override def videoSnapshots(video: Video): F[List[Snapshot]] =
    logger.info(s"Taking video snapshots for id=${video.videoMetadata.id}")
      .productR {
        VideoEnrichmentService
          .snapshotTimestamps(video, VideoEnrichmentServiceImpl.SnapshotCount)
          .toList
          .traverse(createSnapshot(video, _))
      }
      .productL {
        logger.info(s"Completed video snapshots for id=${video.videoMetadata.id}")
      }


  override def snapshotFileResource(
    videoPath: String,
    snapshotPath: String,
    videoTimestamp: FiniteDuration
  ): F[FileResource] =
    videoSnapshotService.takeSnapshot(videoPath, videoTimestamp, snapshotPath)

  private def createSnapshot(video: Video, videoTimestamp: FiniteDuration): F[Snapshot] = {
    val snapshotPath =
      s"${storageConfiguration.imageFolder}/${video.videoMetadata.id}-snapshot-${videoTimestamp.toMillis}.${snapshotMediaType.subType}"

    snapshotFileResource(video.fileResource.path, snapshotPath, videoTimestamp)
      .flatMap { fileResource =>
        val snapshot = Snapshot(video.videoMetadata.id, fileResource, videoTimestamp)

        transaction {
          fileResourceDao
            .insert(fileResource)
            .productR(snapshotDao.insert(snapshot))
        }.as(snapshot)
      }
  }
}

object VideoEnrichmentServiceImpl {
  private val SnapshotCount = 12
}
