package com.ruchij.batch.services.sync

import cats.data.OptionT
import cats.effect.{Async, MonadCancelThrow}
import cats.implicits._
import cats.{Applicative, ApplicativeError, Functor, MonadError, ~>}
import com.ruchij.batch.config.BatchStorageConfiguration
import com.ruchij.batch.daos.filesync.FileSyncDao
import com.ruchij.batch.daos.filesync.models.FileSync
import com.ruchij.batch.exceptions.SynchronizationException
import com.ruchij.batch.services.enrichment.VideoEnrichmentService
import com.ruchij.batch.services.sync.SynchronizationServiceImpl._
import com.ruchij.batch.services.sync.models.FileSyncResult.{ExistingVideo, IgnoredFile, SyncError, VideoSynced}
import com.ruchij.batch.services.sync.models.{FileSyncResult, SynchronizationResult}
import com.ruchij.batch.services.video.BatchVideoService
import com.ruchij.core.daos.resource.FileResourceDao
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.scheduling.SchedulingDao
import com.ruchij.core.daos.scheduling.models.{ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.daos.videometadata.VideoMetadataDao
import com.ruchij.core.daos.videometadata.models.{VideoMetadata, VideoSite}
import com.ruchij.core.exceptions.ResourceNotFoundException
import com.ruchij.core.logging.Logger
import com.ruchij.core.services.cli.CliCommandRunner
import com.ruchij.core.services.hashing.HashingService
import com.ruchij.core.services.repository.FileRepositoryService.FileRepository
import com.ruchij.core.services.repository.FileTypeDetector
import com.ruchij.core.types.FunctionKTypes._
import com.ruchij.core.types.JodaClock
import fs2.Stream
import org.http4s.{MediaType, Uri}

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.util.matching.Regex

class SynchronizationServiceImpl[F[+ _]: Async: JodaClock, A, T[_]: MonadError[*[_], Throwable]](
  fileRepositoryService: FileRepository[F, A],
  fileResourceDao: FileResourceDao[T],
  videoMetadataDao: VideoMetadataDao[T],
  schedulingDao: SchedulingDao[T],
  fileSyncDao: FileSyncDao[T],
  batchVideoService: BatchVideoService[F],
  videoEnrichmentService: VideoEnrichmentService[F],
  hashingService: HashingService[F],
  cliCommandRunner: CliCommandRunner[F],
  fileTypeDetector: FileTypeDetector[F, A],
  storageConfiguration: BatchStorageConfiguration
)(implicit transaction: T ~> F)
    extends SynchronizationService[F] {

  private val logger = Logger[SynchronizationServiceImpl[F, A, T]]

  override val sync: F[SynchronizationResult] =
    Stream
      .emits[F, String](storageConfiguration.videoFolder :: storageConfiguration.otherVideoFolders)
      .flatMap(fileRepositoryService.list)
      .mapAsyncUnordered(MaxConcurrentSyncCount) { filePath =>
        isFileSupported(filePath)
          .flatMap { isVideoFilePath =>
            if (isVideoFilePath) syncVideo(filePath)
            else
              logger
                .debug(s"Ignoring $filePath")
                .productR(Applicative[F].pure(IgnoredFile(filePath)))
          }
      }
      .evalTap {
        case VideoSynced(video) =>
          logger.info[F](s"Sync completed for ${video.fileResource.path}")

        case _ => Applicative[F].unit
      }
      .fold(SynchronizationResult.Zero)(_ + _)
      .compile
      .lastOrError

  def isFileSupported(filePath: String): F[Boolean] =
    if (MediaType.video.all.exists(_.fileExtensions.exists(extension => filePath.endsWith("." + extension)))) {
      MonadCancelThrow[F]
        .handleError {
          for {
            path <- fileRepositoryService.backedType(filePath)
            fileType <- fileTypeDetector.detect(path)
            isSupported = MediaType.video.all.contains(fileType)
          } yield isSupported
        } { _ =>
          false
        }
    } else
      Applicative[F].pure(false)

  def syncVideo(videoPath: String): F[FileSyncResult] =
    JodaClock[F].timestamp.flatMap { startTimestamp =>
      transaction {
        OptionT(fileResourceDao.findByPath(videoPath).map(_.as((): Unit)))
          .orElse {
            OptionT
              .fromOption[T](videoIdFromVideoFile(videoPath))
              .flatMapF(videoId => schedulingDao.getById(videoId).map(_.as((): Unit)))
          }
          .orElse {
            OptionT(fileSyncDao.findByPath(videoPath))
              .flatMap { fileSync =>
                if (fileSync.syncedAt.isEmpty && fileSync.lockedAt.isBefore(startTimestamp.minusMinutes(1)))
                  OptionT(fileSyncDao.deleteByPath(videoPath)).productR(OptionT.none[T, Unit])
                else OptionT.some[T]((): Unit)
              }
          }
          .value
      }.flatMap {
        case None =>
          transaction(fileSyncDao.insert(FileSync(startTimestamp, videoPath, None)))
            .flatMap { count =>
              if (count == 1)
                addVideo(videoPath)
                  .productL {
                    JodaClock[F].timestamp.flatMap { finishTimestamp =>
                      transaction(fileSyncDao.complete(videoPath, finishTimestamp))
                    }
                  } else Applicative[F].pure(IgnoredFile(videoPath))
            }
            .handleErrorWith { throwable =>
              logger.warn(throwable.getMessage).as(IgnoredFile(videoPath))
            }

        case _ => Applicative[F].pure(ExistingVideo(videoPath))
      }
    }

  def addVideo(videoPath: String): F[FileSyncResult] =
    videoFromPath(videoPath)
      .flatMap { video =>
        saveVideo(video).recoverWith {
          case throwable =>
            batchVideoService
              .deleteById(video.videoMetadata.id, deleteVideoFile = false)
              .productR(ApplicativeError[F, Throwable].raiseError(throwable))
        }
      }
      .map[FileSyncResult](VideoSynced)
      .recoverWith {
        errorHandler[F](videoPath) {
          throwable =>
            logger.error[F](s"Unable to add video file at: $videoPath", throwable)
        }
      }

  def videoFromPath(videoPath: String): F[Video] =
    for {
      _ <- logger.info[F](s"Sync started for $videoPath")
      duration <- videoDuration(videoPath)

      size <- OptionT(fileRepositoryService.size(videoPath))
        .getOrElseF {
          ApplicativeError[F, Throwable].raiseError(ResourceNotFoundException(s"File not found at $videoPath"))
        }

      path <- fileRepositoryService.backedType(videoPath)
      mediaType <- fileTypeDetector.detect(path)

      videoId <- hashingService.hash(videoPath).map(hash => s"local-$hash")

      snapshot <- videoEnrichmentService.snapshotFileResource(
        videoPath,
        s"${storageConfiguration.imageFolder}/thumbnail-$videoId.${videoEnrichmentService.snapshotMediaType.subType}",
        FiniteDuration(
          (duration * SynchronizationServiceImpl.VideoThumbnailSnapshotTimestamp).toMillis,
          TimeUnit.MILLISECONDS
        )
      )

      uri <- Uri.fromString(Uri.encode(videoPath)).toType[F, Throwable]

      timestamp <- JodaClock[F].timestamp

      videoTitle = fileName(videoPath)
      videoMetadata = VideoMetadata(uri, videoId, VideoSite.Local, videoTitle, duration, size, snapshot)
      videoFileResource = FileResource(videoId, timestamp, videoPath, mediaType, size)

    } yield Video(videoMetadata, videoFileResource, FiniteDuration(0, TimeUnit.MILLISECONDS))

  def saveVideo(video: Video): F[Video] = {
    JodaClock[F].timestamp
      .flatMap { timestamp =>
        transaction {
          fileResourceDao
            .insert(video.videoMetadata.thumbnail)
            .productR(videoMetadataDao.insert(video.videoMetadata))
            .productR {
              schedulingDao.insert {
                ScheduledVideoDownload(
                  timestamp,
                  timestamp,
                  SchedulingStatus.Completed,
                  video.videoMetadata.size,
                  video.videoMetadata,
                  Some(timestamp)
                )
              }
            }
            .productR(fileResourceDao.insert(video.fileResource))
        }
      }
      .productR(batchVideoService.insert(video.videoMetadata.id, video.fileResource.id))
      .flatTap { video =>
        MonadCancelThrow[F].handleErrorWith(videoEnrichmentService.videoSnapshots(video).as((): Unit)) { _ =>
          Applicative[F].unit
        }
      }
  }

  def videoDuration(videoPath: String): F[FiniteDuration] =
    cliCommandRunner.run(s"""ffprobe -i "$videoPath" -show_entries format=duration -v quiet -print_format csv="p=0"""")
      .compile
      .string
      .flatMap { output =>
        output.toDoubleOption match {
          case None =>
            ApplicativeError[F, Throwable].raiseError {
              SynchronizationException(s"Unable to determine video duration for file at $videoPath")
            }

          case Some(seconds) =>
            Applicative[F].pure {
              FiniteDuration(math.floor(seconds).toLong, TimeUnit.SECONDS)
            }
        }
      }
}

object SynchronizationServiceImpl {
  private val VideoThumbnailSnapshotTimestamp = 0.1
  private val PathDelimiter = "[/\\\\]"
  private val MaxConcurrentSyncCount = 8
  private val VideoFileVideoIdPattern: Regex = "^([^-]+)-([^-\\.]+).*".r

  def errorHandler[F[_]: Functor](
    videoPath: String
  )(handler: PartialFunction[Throwable, F[_]]): PartialFunction[Throwable, F[SyncError]] = {
    case throwable =>
      handler(throwable).as(SyncError(throwable, videoPath))
  }

  def fileName(path: String): String =
    path.split(PathDelimiter).lastOption.getOrElse(path)

  def videoIdFromVideoFile(videoFilePath: String): Option[String] =
    fileName(videoFilePath) match {
      case VideoFileVideoIdPattern(site, videoHash) => Some(s"$site-$videoHash")
      case _ => None
    }

}
