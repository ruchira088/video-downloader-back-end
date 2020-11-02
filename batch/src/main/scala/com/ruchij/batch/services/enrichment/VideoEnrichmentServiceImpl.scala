package com.ruchij.batch.services.enrichment

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream

import cats.data.OptionT
import cats.effect.{Blocker, Clock, ContextShift, Sync}
import cats.implicits._
import cats.{ApplicativeError, Monad, ~>}
import com.ruchij.core.config.DownloadConfiguration
import com.ruchij.core.daos.resource.FileResourceDao
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.snapshot.SnapshotDao
import com.ruchij.core.daos.snapshot.models.Snapshot
import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.exceptions.{CorruptedFrameGrabException, ResourceNotFoundException}
import com.ruchij.core.services.hashing.HashingService
import com.ruchij.core.services.repository.FileRepositoryService.FileRepository
import com.ruchij.core.types.JodaClock
import fs2.Stream
import javax.imageio.ImageIO
import net.coobird.thumbnailator.Thumbnails
import org.http4s.MediaType
import org.jcodec.api.FrameGrab
import org.jcodec.scale.AWTUtil

import scala.concurrent.duration.FiniteDuration

class VideoEnrichmentServiceImpl[F[_]: Sync: Clock: ContextShift, A, T[_]: Monad](
  fileRepository: FileRepository[F, A],
  hashingService: HashingService[F],
  snapshotDao: SnapshotDao[T],
  fileResourceDao: FileResourceDao[T],
  ioBlocker: Blocker,
  downloadConfiguration: DownloadConfiguration
)(implicit seekableByteChannelConverter: SeekableByteChannelConverter[F, A], transaction: T ~> F)
    extends VideoEnrichmentService[F] {

  override val snapshotMediaType: MediaType = MediaType.image.png

  override def videoSnapshots(video: Video): F[Seq[Snapshot]] =
    ioBlocker.blockOn {
      for {
        frameGrab <- createFrameGrab(video.fileResource.path)

        snapshots <- VideoEnrichmentService
          .snapshotTimestamps(video, VideoEnrichmentServiceImpl.SnapshotCount)
          .toList
          .traverse(createSnapshot(video, frameGrab, _))
      } yield snapshots
    }

  override def snapshotFileResource(
    videoPath: String,
    snapshotPath: String,
    videoTimestamp: FiniteDuration
  ): F[FileResource] =
    ioBlocker.blockOn {
      for {
        frameGrab <- createFrameGrab(videoPath)
        snapshot <- snapshotFileResource(snapshotPath, frameGrab, videoTimestamp)
      } yield snapshot
    }

  def createFrameGrab(videoPath: String): F[FrameGrab] =
    for {
      backedType <- fileRepository.backedType(videoPath)
      seekableByteChannel <- seekableByteChannelConverter.convert(backedType)
      frameGrab <- Sync[F].delay(FrameGrab.createFrameGrab(seekableByteChannel))
    } yield frameGrab

  def createSnapshot(video: Video, frameGrab: FrameGrab, videoTimestamp: FiniteDuration): F[Snapshot] = {
    val key =
      s"${downloadConfiguration.imageFolder}/${video.videoMetadata.id}-snapshot-${videoTimestamp.toMillis}.${snapshotMediaType.subType}"

    snapshotFileResource(key, frameGrab, videoTimestamp)
      .flatMap { fileResource =>
        val snapshot = Snapshot(video.videoMetadata.id, fileResource, videoTimestamp)

        transaction {
          fileResourceDao.insert(fileResource)
            .productR(snapshotDao.insert(snapshot))
        }
            .as(snapshot)
      }
  }

  def snapshotFileResource(
    snapshotPath: String,
    frameGrab: FrameGrab,
    videoTimestamp: FiniteDuration
  ): F[FileResource] =
    fileRepository
      .write(snapshotPath, grabSnapshot(frameGrab, videoTimestamp))
      .compile
      .drain
      .productR {
        for {
          timestamp <- JodaClock[F].timestamp

          size <- OptionT(fileRepository.size(snapshotPath))
            .getOrElseF {
              ApplicativeError[F, Throwable].raiseError {
                ResourceNotFoundException {
                  s"Unable deduce the file size. File not found at: $snapshotPath"
                }
              }
            }

          hashedPath <- hashingService.hash(snapshotPath)

          fileResource = FileResource(
            s"snapshot-$hashedPath-${videoTimestamp.toMillis}",
            timestamp,
            snapshotPath,
            snapshotMediaType,
            size
          )
        } yield fileResource
      }

  def grabSnapshot(frameGrab: FrameGrab, videoTimestamp: FiniteDuration): Stream[F, Byte] =
    Stream
      .eval {
        OptionT(Sync[F].delay(Option(frameGrab.seekToSecondSloppy(videoTimestamp.toSeconds.toDouble).getNativeFrame)))
          .getOrElseF(ApplicativeError[F, Throwable].raiseError(CorruptedFrameGrabException))
      }
      .evalMap { picture =>
        val outputStream = new ByteArrayOutputStream()

        Sync[F]
          .delay {
            ImageIO.write(
              scaleImage(
                AWTUtil.toBufferedImage(picture),
                VideoEnrichmentServiceImpl.ScaledImageWidth,
                VideoEnrichmentServiceImpl.ScaledImageHeight
              ),
              snapshotMediaType.subType,
              outputStream)
          }
          .as(outputStream)

      }
      .flatMap(outputStream => Stream.emits[F, Byte](outputStream.toByteArray))

  def scaleImage(bufferedImage: BufferedImage, width: Int, height: Int): BufferedImage =
    Thumbnails.of(bufferedImage).size(width, height).asBufferedImage()
}

object VideoEnrichmentServiceImpl {
  val SnapshotCount = 12
  val ScaledImageWidth = 640
  val ScaledImageHeight = 360
}
