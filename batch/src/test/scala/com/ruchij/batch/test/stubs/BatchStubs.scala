package com.ruchij.batch.test.stubs

import cats.data.OptionT
import cats.effect.{IO, Ref, Resource}
import com.ruchij.batch.services.enrichment.VideoEnrichmentService
import com.ruchij.batch.services.scheduling.BatchSchedulingService
import com.ruchij.batch.services.video.BatchVideoService
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.scheduling.models.{ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.daos.snapshot.models.Snapshot
import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.services.download.DownloadService
import com.ruchij.core.services.download.models.DownloadResult
import com.ruchij.core.services.repository.RepositoryService
import com.ruchij.core.services.scheduling.models.{DownloadProgress, WorkerStatusUpdate}
import com.ruchij.core.services.video.VideoAnalysisService.VideoMetadataResult
import com.ruchij.core.services.video.models.{VideoAnalysisResult, YTDownloaderProgress}
import com.ruchij.core.services.video.{VideoAnalysisService, YouTubeVideoDownloader}
import fs2.Stream
import org.http4s.{MediaType, Uri}

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

/**
  * Hand-written test doubles for the batch services. They return canned values and record the calls made to them,
  * so specs can assert on the interactions without a mocking framework.
  */
object BatchStubs {

  class StubBatchSchedulingService(
    scheduledVideoDownload: ScheduledVideoDownload,
    val statusUpdates: Ref[IO, List[(String, SchedulingStatus)]],
    val completedIds: Ref[IO, List[String]],
    val progressUpdates: Ref[IO, List[DownloadProgress]]
  ) extends BatchSchedulingService[IO] {
    override val acquireTask: OptionT[IO, ScheduledVideoDownload] = OptionT.none

    override val staleTask: OptionT[IO, ScheduledVideoDownload] = OptionT.none

    override def updateTimedOutTasks(duration: FiniteDuration): IO[Seq[ScheduledVideoDownload]] = IO.pure(Seq.empty)

    override def updateSchedulingStatusById(id: String, status: SchedulingStatus): IO[ScheduledVideoDownload] =
      statusUpdates.update(_ :+ (id, status)).as(scheduledVideoDownload.copy(status = status))

    override def setErrorById(id: String, throwable: Throwable): IO[ScheduledVideoDownload] =
      statusUpdates.update(_ :+ (id, SchedulingStatus.Error)).as(scheduledVideoDownload)

    override def publishDownloadProgress(id: String, downloadedBytes: Long): IO[Unit] =
      progressUpdates.update(_ :+ DownloadProgress(id, Instant.EPOCH, downloadedBytes))

    override def completeScheduledVideoDownload(id: String): IO[ScheduledVideoDownload] =
      completedIds.update(_ :+ id).as(scheduledVideoDownload.copy(status = SchedulingStatus.Completed))

    override def publishScheduledVideoDownload(id: String): IO[ScheduledVideoDownload] = IO.pure(scheduledVideoDownload)

    override def deleteById(id: String): IO[ScheduledVideoDownload] = IO.pure(scheduledVideoDownload)

    override def updateSchedulingStatus(from: SchedulingStatus, to: SchedulingStatus): IO[Seq[ScheduledVideoDownload]] =
      IO.pure(Seq.empty)

    override def subscribeToScheduledVideoDownloadUpdates(groupId: String): Stream[IO, ScheduledVideoDownload] =
      Stream.empty

    override def subscribeToWorkerStatusUpdates(groupId: String): Stream[IO, WorkerStatusUpdate] = Stream.empty
  }

  object StubBatchSchedulingService {
    def create(scheduledVideoDownload: ScheduledVideoDownload): IO[StubBatchSchedulingService] =
      for {
        statusUpdates <- Ref.of[IO, List[(String, SchedulingStatus)]](List.empty)
        completedIds <- Ref.of[IO, List[String]](List.empty)
        progressUpdates <- Ref.of[IO, List[DownloadProgress]](List.empty)
      } yield new StubBatchSchedulingService(scheduledVideoDownload, statusUpdates, completedIds, progressUpdates)
  }

  class StubBatchVideoService(video: Video) extends BatchVideoService[IO] {
    override def insert(videoMetadataKey: String, fileResourceKey: String): IO[Video] = IO.pure(video)

    override def incrementWatchTime(videoId: String, duration: FiniteDuration): IO[FiniteDuration] = IO.pure(duration)

    override def fetchByVideoFileResourceId(videoFileResourceId: String): IO[Video] = IO.pure(video)

    override def update(videoId: String, size: Long): IO[Video] = IO.pure(video)

    override def deleteById(videoId: String, deleteVideoFile: Boolean): IO[Video] = IO.pure(video)
  }

  class StubVideoEnrichmentService(timestamp: Instant) extends VideoEnrichmentService[IO] {
    override val snapshotMediaType: MediaType = MediaType.image.png

    override def snapshotFileResource(
      videoPath: String,
      snapshotPath: String,
      videoTimestamp: FiniteDuration
    ): IO[FileResource] =
      IO.pure(FileResource("snapshot-id", timestamp, snapshotPath, MediaType.image.png, 1000))

    override def videoSnapshots(video: Video): IO[List[Snapshot]] = IO.pure(List.empty)
  }

  class StubRepositoryService(
    existsResult: Boolean = true,
    fileSize: Option[Long] = None,
    fileType: Option[MediaType] = None,
    files: List[String] = List.empty
  ) extends RepositoryService[IO] {
    override type BackedType = String

    override def write(key: String, data: Stream[IO, Byte]): Stream[IO, Nothing] = Stream.empty

    override def read(key: String, start: Option[Long], end: Option[Long]): IO[Option[Stream[IO, Byte]]] =
      IO.pure(None)

    override def size(key: String): IO[Option[Long]] = IO.pure(fileSize)

    override def fileType(key: String): IO[Option[MediaType]] = IO.pure(fileType)

    override def delete(key: String): IO[Boolean] = IO.pure(true)

    override def list(prefix: String): Stream[IO, String] = Stream.emits(files.filter(_.startsWith(prefix)))

    override def exists(key: String): IO[Boolean] = IO.pure(existsResult)

    override def backedType(key: String): IO[String] = IO.pure(key)
  }

  class StubVideoAnalysisService(downloadUriResult: Uri, videoDuration: FiniteDuration)
      extends VideoAnalysisService[IO] {
    override def downloadUri(videoUri: Uri): IO[Uri] = IO.pure(downloadUriResult)

    override def videoDurationFromPath(videoPath: String): IO[FiniteDuration] = IO.pure(videoDuration)

    override def metadata(uri: Uri): IO[VideoMetadataResult] =
      IO.raiseError(new NotImplementedError("metadata is not implemented in the stub"))

    override def analyze(uri: Uri): IO[VideoAnalysisResult] =
      IO.raiseError(new NotImplementedError("analyze is not implemented in the stub"))
  }

  class StubDownloadService(downloadResult: DownloadResult[IO]) extends DownloadService[IO] {
    override def download(uri: Uri, fileKey: String): Resource[IO, DownloadResult[IO]] =
      Resource.pure(downloadResult)
  }

  class StubYouTubeVideoDownloader(progress: Stream[IO, YTDownloaderProgress] = Stream.empty)
      extends YouTubeVideoDownloader[IO] {
    override def downloadVideo(uri: Uri, pathWithoutExtension: String): Stream[IO, YTDownloaderProgress] = progress

    override val version: IO[String] = IO.pure("test-version")

    override val supportedSites: IO[Seq[String]] = IO.pure(Seq("youtube.com", "youtu.be"))

    override def videoInformation(uri: Uri): IO[VideoAnalysisResult] =
      IO.raiseError(new NotImplementedError("videoInformation is not implemented in the stub"))
  }
}
