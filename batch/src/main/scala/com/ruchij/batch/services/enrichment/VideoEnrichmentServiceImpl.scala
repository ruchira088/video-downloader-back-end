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
import com.ruchij.core.types.RandomGenerator
import org.http4s.MediaType

import java.util.UUID
import scala.concurrent.duration.FiniteDuration

class VideoEnrichmentServiceImpl[F[_]: Sync: RandomGenerator[*[_], UUID],T[_]: Monad](
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
          .snapshotTimestamps(video, VideoEnrichmentService.SnapshotCount)
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

  private def createSnapshot(video: Video, videoTimestamp: FiniteDuration): F[Snapshot] =
   for {
     suffix <- RandomGenerator[F, UUID].generate.map(_.toString.take(5))

     snapshotPath =
       s"${storageConfiguration.imageFolder}/${video.videoMetadata.id}-snapshot-${videoTimestamp.toMillis}-$suffix.${snapshotMediaType.subType}"

     fileResource <- snapshotFileResource(video.fileResource.path, snapshotPath, videoTimestamp)

     snapshot = Snapshot(video.videoMetadata.id, fileResource, videoTimestamp)

     _ <-  transaction { fileResourceDao.insert(fileResource).product(snapshotDao.insert(snapshot)) }
   }
   yield snapshot
}