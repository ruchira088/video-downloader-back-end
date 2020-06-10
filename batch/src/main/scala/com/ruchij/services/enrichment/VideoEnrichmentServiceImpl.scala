package com.ruchij.services.enrichment

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

import cats.{Applicative, ApplicativeError}
import cats.data.OptionT
import cats.effect.{Blocker, Clock, ContextShift, Sync}
import cats.implicits._
import com.ruchij.config.DownloadConfiguration
import com.ruchij.daos.resource.models.FileResource
import com.ruchij.daos.snapshot.SnapshotDao
import com.ruchij.daos.snapshot.models.Snapshot
import com.ruchij.daos.video.models.Video
import com.ruchij.exceptions.InvalidConditionException
import com.ruchij.services.repository.FileRepositoryService.FileRepository
import fs2.Stream
import javax.imageio.ImageIO
import org.http4s.MediaType
import org.jcodec.api.FrameGrab
import org.jcodec.scale.AWTUtil
import org.joda.time.DateTime

import scala.concurrent.duration.FiniteDuration

class VideoEnrichmentServiceImpl[F[_]: Sync: Clock: ContextShift, A](
  fileRepository: FileRepository[F, A],
  snapshotDao: SnapshotDao[F],
  ioBlocker: Blocker,
  downloadConfiguration: DownloadConfiguration
)(implicit seekableByteChannelConverter: SeekableByteChannelConverter[F, A])
    extends VideoEnrichmentService[F] {

  override def videoSnapshots(video: Video): F[Seq[Snapshot]] =
    ioBlocker.blockOn(snapshots(video))

  def snapshots(video: Video): F[Seq[Snapshot]] =
    for {
      backedType <- fileRepository.backedType(video.fileResource.path)
      seekableByteChannel <- seekableByteChannelConverter.convert(backedType)
      frameGrab = FrameGrab.createFrameGrab(seekableByteChannel)

      snapshots <- VideoEnrichmentService
        .snapshotTimestamps(video, VideoEnrichmentServiceImpl.SNAPSHOT_COUNT)
        .toList
        .traverse(createSnapshot(video, frameGrab, _))
    } yield snapshots

  def createSnapshot(video: Video, frameGrab: FrameGrab, videoTimestamp: FiniteDuration): F[Snapshot] = {
    val key =
      s"${downloadConfiguration.imageFolder}/${video.videoMetadata.id}-${videoTimestamp.toSeconds}.${VideoEnrichmentServiceImpl.SNAPSHOT_FORMAT.subType}"

    fileRepository
      .write(key, grabSnapshot(frameGrab, videoTimestamp))
      .compile
      .drain
      .productR {
        for {
          timestamp <- Clock[F].realTime(TimeUnit.MILLISECONDS)
          size <-
            OptionT(fileRepository.size(key))
              .getOrElseF(ApplicativeError[F, Throwable].raiseError(InvalidConditionException))

          fileResource = FileResource(
            s"${video.videoMetadata.id}-$timestamp",
            new DateTime(timestamp),
            key,
            VideoEnrichmentServiceImpl.SNAPSHOT_FORMAT,
            size
          )

          snapshot = Snapshot(video.videoMetadata.id, fileResource, videoTimestamp)

          _ <- snapshotDao.insert(snapshot)
        } yield snapshot
      }
  }

  def grabSnapshot(frameGrab: FrameGrab, videoTimestamp: FiniteDuration): Stream[F, Byte] =
    Stream
      .eval(Applicative[F].pure(new ByteArrayOutputStream()))
      .evalTap { outputStream =>
        Sync[F].delay {
          ImageIO.write(
            AWTUtil.toBufferedImage(frameGrab.seekToSecondSloppy(videoTimestamp.toSeconds.toDouble).getNativeFrame),
            VideoEnrichmentServiceImpl.SNAPSHOT_FORMAT.subType,
            outputStream
          )
        }
      }
      .flatMap(outputStream => Stream.emits[F, Byte](outputStream.toByteArray))
}

object VideoEnrichmentServiceImpl {
  val SNAPSHOT_FORMAT: MediaType = MediaType.image.png

  val SNAPSHOT_COUNT = 10
}
