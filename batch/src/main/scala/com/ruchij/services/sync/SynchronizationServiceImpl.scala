package com.ruchij.services.sync

import java.util.concurrent.TimeUnit

import cats.data.OptionT
import cats.effect.{Blocker, Clock, ContextShift, Sync}
import cats.implicits._
import cats.{Applicative, ApplicativeError, Monad, ~>}
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
import com.ruchij.services.sync.SynchronizationServiceImpl.FileName
import com.ruchij.services.sync.models.SyncResult
import com.ruchij.services.video.VideoService
import com.ruchij.types.FunctionKTypes.eitherToF
import org.http4s.{MediaType, Uri}
import org.jcodec.api.FrameGrab
import org.joda.time.DateTime

import scala.concurrent.duration.FiniteDuration

class SynchronizationServiceImpl[F[_]: Sync: ContextShift: Clock, A, T[_]: Monad](
  fileRepositoryService: FileRepository[F, A],
  fileResourceDao: FileResourceDao[T],
  videoMetadataDao: VideoMetadataDao[T],
  videoService: VideoService[F],
  videoEnrichmentService: VideoEnrichmentService[F],
  hashingService: HashingService[F],
  ioBlocker: Blocker,
  downloadConfiguration: DownloadConfiguration
)(implicit seekableByteChannelConverter: SeekableByteChannelConverter[F, A], transaction: T ~> F)
    extends SynchronizationService[F] {

  private val logger = Logger[F, SynchronizationServiceImpl[F, A, T]]

  override val sync: F[SyncResult] =
    fileRepositoryService
      .list(downloadConfiguration.videoFolder)
      .filter(path => !List(".gitignore", ".DS_Store").exists(path.endsWith))
      .evalMap {
        case path @ FileName(fileName) =>
          transaction(fileResourceDao.findByPath(fileName))
            .flatMap {
              _.fold[F[Option[Video]]](add(path))(_ => Applicative[F].pure(None))
            }
      }
      .collect { case Some(video) => video }
      .evalMap(saveVideo)
      .collect { case Some(video) => video }
      .evalTap { video => logger.infoF(s"Sync completed for ${video.fileResource.path}") }
      .compile
      .drain
      .as(SyncResult())

  def add(videoPath: String): F[Option[Video]] =
    addVideo(videoPath)
      .map[Option[Video]](Some.apply)
      .recoverWith(deleteCorruptedVideoFile(videoPath))

  def addVideo(videoPath: String): F[Video] =
    for {
      _ <- logger.infoF(s"Sync started for $videoPath")
      duration <- videoDuration(videoPath)

      (size, mediaType) <- OptionT(fileRepositoryService.size(videoPath))
        .product(OptionT.pure.apply(MediaType.video.mp4))
        .getOrElseF(
          ApplicativeError[F, Throwable].raiseError(ResourceNotFoundException(s"File not found at $videoPath"))
        )

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

  def saveVideo(video: Video): F[Option[Video]] =
    transaction {
      fileResourceDao
        .insert(video.videoMetadata.thumbnail)
        .productR(videoMetadataDao.insert(video.videoMetadata))
    }.productR(videoService.insert(video.videoMetadata.id, video.fileResource))
      .flatTap(videoEnrichmentService.videoSnapshots)
      .map[Option[Video]](Some.apply)
      .recoverWith(deleteCorruptedVideoFile(video.fileResource.path))

  def videoDuration(videoPath: String): F[FiniteDuration] =
    ioBlocker.blockOn {
      for {
        backedType <- fileRepositoryService.backedType(videoPath)
        seekableByteChannel <- seekableByteChannelConverter.convert(backedType)
        frameGrab = FrameGrab.createFrameGrab(seekableByteChannel)

        seconds <- Sync[F].delay(frameGrab.getVideoTrack.getMeta.getTotalDuration)
      } yield FiniteDuration(math.floor(seconds).toLong, TimeUnit.SECONDS)
    }

  def deleteCorruptedVideoFile[B](videoPath: String): PartialFunction[Throwable, F[Option[B]]] = {
    case CorruptedFrameGrabException =>
      Sync[F]
        .delay(logger.warnF(s"File at $videoPath is corrupted. The file will be deleted"))
        .productR(fileRepositoryService.delete(videoPath))
        .productR(Sync[F].delay(logger.infoF(s"File deleted at $videoPath")))
        .as[Option[B]](None)
  }

}

object SynchronizationServiceImpl {
  val THUMBNAIL_TIMESTAMP = 0.1

  object FileName {
    def unapply(path: String): Option[String] =
      path.split("[/\\\\]").lastOption
  }
}
