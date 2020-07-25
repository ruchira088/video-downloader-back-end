package com.ruchij.services.enrichment

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream

import cats.{ApplicativeError, Monad, ~>}
import cats.data.OptionT
import cats.effect.{Blocker, Clock, ContextShift, Sync}
import cats.implicits._
import com.ruchij.config.DownloadConfiguration
import com.ruchij.daos.resource.FileResourceDao
import com.ruchij.daos.resource.models.FileResource
import com.ruchij.daos.snapshot.SnapshotDao
import com.ruchij.daos.snapshot.models.Snapshot
import com.ruchij.daos.video.models.Video
import com.ruchij.exceptions.{CorruptedFrameGrabException, InvalidConditionException}
import com.ruchij.services.repository.FileRepositoryService.FileRepository
import com.ruchij.types.JodaClock
import fs2.Stream
import javax.imageio.ImageIO
import net.coobird.thumbnailator.Thumbnails
import org.http4s.MediaType
import org.jcodec.api.FrameGrab
import org.jcodec.scale.AWTUtil

import scala.concurrent.duration.FiniteDuration

class VideoEnrichmentServiceImpl[F[_]: Sync: Clock: ContextShift, A, T[_]: Monad](
  fileRepository: FileRepository[F, A],
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
          .snapshotTimestamps(video, VideoEnrichmentServiceImpl.snapshotCount)
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
            .getOrElseF(ApplicativeError[F, Throwable].raiseError(InvalidConditionException))

          fileResource = FileResource(
            s"snapshot-${timestamp.getMillis}-$size",
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
                VideoEnrichmentServiceImpl.scaledImageWidth,
                VideoEnrichmentServiceImpl.scaledImageHeight
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
  val snapshotCount = 12
  val scaledImageWidth = 640
  val scaledImageHeight = 360
}
