package com.ruchij.services.sync

import java.util.concurrent.TimeUnit

import cats.data.OptionT
import cats.effect.{Blocker, Clock, Concurrent, ContextShift, Sync}
import cats.implicits._
import cats.{Applicative, ApplicativeError, Functor, Monad, ~>}
import com.ruchij.config.DownloadConfiguration
import com.ruchij.daos.resource.FileResourceDao
import com.ruchij.daos.resource.models.FileResource
import com.ruchij.daos.video.models.Video
import com.ruchij.daos.videometadata.VideoMetadataDao
import com.ruchij.daos.videometadata.models.{VideoMetadata, VideoSite}
import com.ruchij.exceptions.{CorruptedFrameGrabException, ResourceNotFoundException}
import com.ruchij.logging.Logger
import com.ruchij.services.enrichment.{SeekableByteChannelConverter, VideoEnrichmentService}
import com.ruchij.services.hashing.HashingService
import com.ruchij.services.repository.FileRepositoryService.FileRepository
import com.ruchij.services.repository.FileTypeDetector
import com.ruchij.services.sync.SynchronizationServiceImpl.{errorHandler, fileName, MaxConcurrentSyncCount, SupportedFileTypes}
import com.ruchij.services.sync.models.FileSyncResult.{ExistingVideo, IgnoredFile, SyncError, VideoSynced}
import com.ruchij.services.sync.models.{FileSyncResult, SynchronizationResult}
import com.ruchij.services.video.VideoService
import com.ruchij.types.FunctionKTypes.eitherToF
import com.ruchij.types.JodaClock
import org.http4s.{MediaType, Uri}
import org.jcodec.api.{FrameGrab, UnsupportedFormatException}

import scala.concurrent.duration.FiniteDuration

class SynchronizationServiceImpl[F[+ _]: Concurrent: ContextShift: Clock, A, T[_]: Monad](
  fileRepositoryService: FileRepository[F, A],
  fileResourceDao: FileResourceDao[T],
  videoMetadataDao: VideoMetadataDao[T],
  videoService: VideoService[F],
  videoEnrichmentService: VideoEnrichmentService[F],
  hashingService: HashingService[F],
  fileTypeDetector: FileTypeDetector[F, A],
  ioBlocker: Blocker,
  downloadConfiguration: DownloadConfiguration
)(implicit seekableByteChannelConverter: SeekableByteChannelConverter[F, A], transaction: T ~> F)
    extends SynchronizationService[F] {

  private val logger = Logger[F, SynchronizationServiceImpl[F, A, T]]

  override val sync: F[SynchronizationResult] =
    fileRepositoryService
      .list(downloadConfiguration.videoFolder)
      .mapAsyncUnordered(MaxConcurrentSyncCount) { filePath =>
        isFileSupported(filePath)
          .flatMap { isVideoFilePath =>
            if (isVideoFilePath) syncVideo(filePath) else Applicative[F].pure(IgnoredFile(filePath))
          }
      }
      .evalTap {
        case VideoSynced(video) =>
          logger.infoF(s"Sync completed for ${video.fileResource.path}")

        case _ => Applicative[F].unit
      }
      .fold(SynchronizationResult.zero)(_ + _)
      .compile
      .lastOrError


  def isFileSupported(filePath: String): F[Boolean] =
    if (SupportedFileTypes.exists(fileType => filePath.endsWith("." + fileType.subType)))
      for {
        path <- fileRepositoryService.backedType(filePath)
        fileType <- fileTypeDetector.detect(path)
        isSupported = SupportedFileTypes.contains(fileType)
      } yield isSupported
    else
      Applicative[F].pure(false)

  def syncVideo(videoPath: String): F[FileSyncResult] =
    transaction(fileResourceDao.findByPath(videoPath))
      .flatMap {
        _.fold[F[FileSyncResult]](addVideo(videoPath))(_ => Applicative[F].pure(ExistingVideo(videoPath)))
      }

  def addVideo(videoPath: String): F[FileSyncResult] =
    videoFromPath(videoPath)
      .flatMap { video =>
        saveVideo(video).recoverWith {
          case throwable =>
            videoService.deleteById(video.videoMetadata.id)
              .productR(ApplicativeError[F, Throwable].raiseError(throwable))
        }
      }
      .map[FileSyncResult](VideoSynced)
      .recoverWith {
        errorHandler[F](videoPath) {
          case CorruptedFrameGrabException =>
            logger.warnF(s"Unable to create thumbnail snapshots for video file at $videoPath")

          case _: UnsupportedFormatException =>
            logger
              .warnF(s"Video with an unsupported format at $videoPath")

          case throwable =>
            logger.errorF(s"Unable to add video file at: $videoPath", throwable)
        }
      }

  def videoFromPath(videoPath: String): F[Video] =
    for {
      _ <- logger.infoF(s"Sync started for $videoPath")
      duration <- videoDuration(videoPath)

      size <-
        OptionT(fileRepositoryService.size(videoPath))
          .getOrElseF {
            ApplicativeError[F, Throwable].raiseError(ResourceNotFoundException(s"File not found at $videoPath"))
          }

      path <- fileRepositoryService.backedType(videoPath)
      mediaType <- fileTypeDetector.detect(path)

      videoId <- hashingService.hash(videoPath)

      snapshot <- videoEnrichmentService.snapshotFileResource(
        videoPath,
        s"${downloadConfiguration.imageFolder}/thumbnail-$videoId.${videoEnrichmentService.snapshotMediaType.subType}",
        FiniteDuration((duration * SynchronizationServiceImpl.ThumbnailTimestamp).toMillis, TimeUnit.MILLISECONDS)
      )

      uri <- eitherToF[Throwable, F].apply(Uri.fromString(Uri.encode(videoPath)))

      timestamp <- JodaClock[F].timestamp

      videoTitle = fileName(videoPath)
      videoMetadata = VideoMetadata(uri, videoId, VideoSite.Local, videoTitle, duration, size, snapshot)
      videoFileResource = FileResource(videoId, timestamp, videoPath, mediaType, size)

    } yield Video(videoMetadata, videoFileResource)

  def saveVideo(video: Video): F[Video] =
    transaction {
      fileResourceDao
        .insert(video.videoMetadata.thumbnail)
        .productR(videoMetadataDao.insert(video.videoMetadata))
    }.productR(videoService.insert(video.videoMetadata.id, video.fileResource))
      .flatTap(videoEnrichmentService.videoSnapshots)

  def videoDuration(videoPath: String): F[FiniteDuration] =
    ioBlocker.blockOn {
      for {
        backedType <- fileRepositoryService.backedType(videoPath)
        seekableByteChannel <- seekableByteChannelConverter.convert(backedType)
        frameGrab <- Sync[F].delay(FrameGrab.createFrameGrab(seekableByteChannel))

        seconds <- Sync[F].delay(frameGrab.getVideoTrack.getMeta.getTotalDuration)
      } yield FiniteDuration(math.floor(seconds).toLong, TimeUnit.SECONDS)
    }
}

object SynchronizationServiceImpl {
  val ThumbnailTimestamp = 0.1
  val SupportedFileTypes: List[MediaType] = List(MediaType.video.mp4)
  val PathDelimiter = "[/\\\\]"
  val MaxConcurrentSyncCount = 8

  def errorHandler[F[_]: Functor](
    videoPath: String
  )(handler: PartialFunction[Throwable, F[_]]): PartialFunction[Throwable, F[SyncError]] = {
    case throwable =>
      handler(throwable).as(SyncError(throwable, videoPath))
  }

  def fileName(path: String): String =
    path.split(PathDelimiter).lastOption.getOrElse(path)
}
