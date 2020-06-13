package com.ruchij.services.sync

import java.util.concurrent.TimeUnit

import cats.data.OptionT
import cats.effect.{Blocker, Clock, ContextShift, Sync}
import cats.implicits._
import cats.{Applicative, ApplicativeError}
import com.ruchij.config.DownloadConfiguration
import com.ruchij.daos.resource.FileResourceDao
import com.ruchij.daos.resource.models.FileResource
import com.ruchij.daos.video.models.Video
import com.ruchij.daos.videometadata.VideoMetadataDao
import com.ruchij.daos.videometadata.models.{VideoMetadata, VideoSite}
import com.ruchij.exceptions.ResourceNotFoundException
import com.ruchij.services.enrichment.{SeekableByteChannelConverter, VideoEnrichmentService}
import com.ruchij.services.hashing.HashingService
import com.ruchij.services.repository.FileRepositoryService.FileRepository
import com.ruchij.services.sync.SynchronizationServiceImpl.FileName
import com.ruchij.services.sync.models.SyncResult
import com.ruchij.services.video.VideoService
import com.ruchij.types.FunctionKTypes.eitherToF
import org.http4s.{MediaType, Uri}
import org.jcodec.api.FrameGrab
import org.joda.time.DateTime

import scala.concurrent.duration.FiniteDuration

class SynchronizationServiceImpl[F[_]: Sync: ContextShift: Clock, A](
  fileRepositoryService: FileRepository[F, A],
  fileResourceDao: FileResourceDao[F],
  videoMetadataDao: VideoMetadataDao[F],
  videoService: VideoService[F],
  videoEnrichmentService: VideoEnrichmentService[F],
  hashingService: HashingService[F],
  ioBlocker: Blocker,
  downloadConfiguration: DownloadConfiguration
)(implicit seekableByteChannelConverter: SeekableByteChannelConverter[F, A])
    extends SynchronizationService[F] {

  override val sync: F[SyncResult] =
    fileRepositoryService
      .list(downloadConfiguration.videoFolder)
      .filter(path => !List(".gitignore", ".DS_Store").exists(path.endsWith))
      .evalMap {
        case path @ FileName(fileName) =>
          fileResourceDao.findByPath(fileName)
            .flatMap {
              _.fold[F[Option[Video]]](add(path).map(Some.apply))(_ => Applicative[F].pure(None))
            }
      }
      .collect { case Some(video) => video }
      .evalMap(saveVideo)
      .evalTap { video => Sync[F].delay(println(s"Completed: ${video.fileResource.path}")) }
      .compile
      .drain
      .as(SyncResult())

  def add(videoPath: String): F[Video] =
    for {
      _ <- Sync[F].delay(println(s"Syncing $videoPath..."))
      duration <- videoDuration(videoPath)

      (size, mediaType) <-
        OptionT(fileRepositoryService.size(videoPath))
          .product(OptionT.pure.apply(MediaType.video.mp4))
          .getOrElseF(ApplicativeError[F, Throwable].raiseError(ResourceNotFoundException(s"File not found at $videoPath")))

      currentTimestamp <- Clock[F].realTime(TimeUnit.MILLISECONDS)

      snapshot <- videoEnrichmentService.snapshotFileResource(
        videoPath,
        s"${downloadConfiguration.imageFolder}/$currentTimestamp-$size.${videoEnrichmentService.snapshotMediaType.subType}",
        FiniteDuration((duration * SynchronizationServiceImpl.THUMBNAIL_TIMESTAMP).toMillis, TimeUnit.MILLISECONDS)
      )

      videoId <- hashingService.hash(videoPath)
      uri <- eitherToF[Throwable, F].apply(Uri.fromString(videoPath))

      videoMetadata = VideoMetadata(uri, videoId, VideoSite.Local, "sample", duration, size, snapshot)
      videoFileResource = FileResource(videoId, new DateTime(currentTimestamp), videoPath, mediaType, size)

    } yield Video(videoMetadata, videoFileResource)

  def saveVideo(video: Video): F[Video] =
    for {
      _ <- videoMetadataDao.add(video.videoMetadata)
      savedVideo <- videoService.insert(video.videoMetadata.id, video.fileResource)
      _ <- videoEnrichmentService.videoSnapshots(savedVideo)
    }
    yield savedVideo

  def videoDuration(videoPath: String): F[FiniteDuration] =
    ioBlocker.blockOn {
      for {
        backedType <- fileRepositoryService.backedType(videoPath)
        seekableByteChannel <- seekableByteChannelConverter.convert(backedType)
        frameGrab = FrameGrab.createFrameGrab(seekableByteChannel)

        seconds <- Sync[F].delay(frameGrab.getVideoTrack.getMeta.getTotalDuration)
      } yield FiniteDuration(math.floor(seconds).toLong, TimeUnit.SECONDS)
    }
}

object SynchronizationServiceImpl {
  val THUMBNAIL_TIMESTAMP = 0.1

  object FileName {
    def unapply(path: String): Option[String] =
      path.split("[/\\\\]").lastOption
  }
}
