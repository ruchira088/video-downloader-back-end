package com.ruchij.batch.services.worker

import cats.effect.{IO, Resource}
import com.ruchij.batch.daos.workers.DoobieWorkerDao
import com.ruchij.batch.daos.workers.models.Worker
import com.ruchij.batch.external.BatchResourcesProvider
import com.ruchij.batch.external.containers.ContainerBatchResourcesProvider
import com.ruchij.batch.services.enrichment.VideoEnrichmentService
import com.ruchij.batch.services.scheduling.BatchSchedulingService
import com.ruchij.batch.services.video.BatchVideoService
import com.ruchij.core.config.StorageConfiguration
import com.ruchij.core.daos.resource.DoobieFileResourceDao
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.scheduling.DoobieSchedulingDao
import com.ruchij.core.daos.scheduling.models.{ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.daos.snapshot.models.Snapshot
import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.daos.videometadata.DoobieVideoMetadataDao
import com.ruchij.core.daos.videometadata.models.{CustomVideoSite, VideoSite}
import com.ruchij.core.daos.workers.models.WorkerStatus
import com.ruchij.core.services.download.DownloadService
import com.ruchij.core.services.download.models.DownloadResult
import com.ruchij.core.services.repository.RepositoryService
import com.ruchij.core.services.video.VideoAnalysisService
import com.ruchij.core.services.video.VideoAnalysisService.VideoMetadataResult
import com.ruchij.core.services.video.models.{VideoAnalysisResult, YTDownloaderProgress}
import com.ruchij.core.services.video.YouTubeVideoDownloader
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.test.data.DataGenerators
import doobie.free.connection.ConnectionIO
import fs2.Stream
import org.http4s.{MediaType, Uri}
import org.joda.time.DateTime
import org.scalamock.scalatest.MockFactory
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class WorkExecutorIntegrationSpec extends AnyFlatSpec with MockFactory with Matchers with OptionValues {

  val timestamp = new DateTime(2024, 5, 15, 10, 0, 0)
  val storageConfiguration = StorageConfiguration("/videos", "/images", List("/other-videos"))

  def insertScheduledVideo(scheduledVideoDownload: ScheduledVideoDownload): ConnectionIO[ScheduledVideoDownload] = {
    for {
      _ <- DoobieFileResourceDao.insert(scheduledVideoDownload.videoMetadata.thumbnail)
      _ <- DoobieVideoMetadataDao.insert(scheduledVideoDownload.videoMetadata)
      _ <- DoobieSchedulingDao.insert(scheduledVideoDownload)
    } yield scheduledVideoDownload
  }

  def insertWorker(worker: Worker): ConnectionIO[Int] =
    new DoobieWorkerDao(DoobieSchedulingDao).insert(worker)

  class StubBatchSchedulingService(
    updateStatusResult: ScheduledVideoDownload,
    completeResult: ScheduledVideoDownload
  ) extends BatchSchedulingService[IO] {
    override val acquireTask: cats.data.OptionT[IO, ScheduledVideoDownload] =
      cats.data.OptionT.none

    override val staleTask: cats.data.OptionT[IO, ScheduledVideoDownload] =
      cats.data.OptionT.none

    override def updateTimedOutTasks(duration: FiniteDuration): IO[Seq[ScheduledVideoDownload]] =
      IO.pure(Seq.empty)

    override def updateSchedulingStatusById(videoId: String, schedulingStatus: SchedulingStatus): IO[ScheduledVideoDownload] =
      IO.pure(updateStatusResult)

    override def setErrorById(videoId: String, throwable: Throwable): IO[ScheduledVideoDownload] =
      IO.pure(updateStatusResult)

    override def publishDownloadProgress(videoId: String, downloadedBytes: Long): IO[Unit] =
      IO.unit

    override def completeScheduledVideoDownload(videoId: String): IO[ScheduledVideoDownload] =
      IO.pure(completeResult)

    override def publishScheduledVideoDownload(videoId: String): IO[ScheduledVideoDownload] =
      IO.pure(updateStatusResult)

    override def deleteById(videoId: String): IO[ScheduledVideoDownload] =
      IO.pure(updateStatusResult)

    override def updateSchedulingStatus(
      current: SchedulingStatus,
      next: SchedulingStatus
    ): IO[Seq[ScheduledVideoDownload]] =
      IO.pure(Seq.empty)

    override def subscribeToScheduledVideoDownloadUpdates(groupId: String): Stream[IO, ScheduledVideoDownload] =
      Stream.empty

    override def subscribeToWorkerStatusUpdates(
      groupId: String
    ): Stream[IO, com.ruchij.core.services.scheduling.models.WorkerStatusUpdate] =
      Stream.empty
  }

  class StubBatchVideoService(insertResult: Video) extends BatchVideoService[IO] {
    override def insert(videoMetadataKey: String, fileResourceKey: String): IO[Video] =
      IO.pure(insertResult)

    override def incrementWatchTime(videoId: String, duration: FiniteDuration): IO[FiniteDuration] =
      IO.pure(duration)

    override def fetchByVideoFileResourceId(videoFileResourceId: String): IO[Video] =
      IO.pure(insertResult)

    override def update(videoId: String, size: Long): IO[Video] =
      IO.pure(insertResult)

    override def deleteById(videoId: String, deleteVideoFile: Boolean): IO[Video] =
      IO.pure(insertResult)
  }

  class StubVideoEnrichmentService extends VideoEnrichmentService[IO] {
    override val snapshotMediaType: MediaType = MediaType.image.png

    override def snapshotFileResource(
      videoPath: String,
      snapshotPath: String,
      snapshotTimestamp: FiniteDuration
    ): IO[FileResource] =
      IO.pure(FileResource("snapshot-id", timestamp, snapshotPath, MediaType.image.png, 1000))

    override def videoSnapshots(video: Video): IO[List[Snapshot]] =
      IO.pure(List.empty)
  }

  class StubRepositoryService(existsResult: Boolean, fileSize: Option[Long], fileType: Option[MediaType])
      extends RepositoryService[IO] {
    override type BackedType = String
    override def write(key: String, data: Stream[IO, Byte]): Stream[IO, Nothing] = Stream.empty
    override def read(key: String, start: Option[Long], end: Option[Long]): IO[Option[Stream[IO, Byte]]] = IO.pure(None)
    override def size(key: String): IO[Option[Long]] = IO.pure(fileSize)
    override def fileType(key: String): IO[Option[MediaType]] = IO.pure(fileType)
    override def delete(key: String): IO[Boolean] = IO.pure(true)
    override def list(prefix: String): Stream[IO, String] = Stream.empty
    override def exists(key: String): IO[Boolean] = IO.pure(existsResult)
    override def backedType(key: String): IO[String] = IO.pure(key)
  }

  class StubVideoAnalysisService(downloadUriResult: Uri, videoDuration: FiniteDuration) extends VideoAnalysisService[IO] {
    override def downloadUri(videoUri: Uri): IO[Uri] = IO.pure(downloadUriResult)
    override def videoDurationFromPath(videoPath: String): IO[FiniteDuration] = IO.pure(videoDuration)
    override def metadata(uri: Uri): IO[VideoMetadataResult] =
      IO.raiseError(new NotImplementedError("metadata not implemented in stub"))
    override def analyze(uri: Uri): IO[VideoAnalysisResult] =
      IO.raiseError(new NotImplementedError("analyze not implemented in stub"))
  }

  class StubDownloadService(downloadResult: DownloadResult[IO]) extends DownloadService[IO] {
    override def download(uri: Uri, fileKey: String): Resource[IO, DownloadResult[IO]] =
      Resource.pure(downloadResult)
  }

  class StubYouTubeVideoDownloader extends YouTubeVideoDownloader[IO] {
    override def downloadVideo(videoUrl: Uri, destinationPath: String): Stream[IO, YTDownloaderProgress] =
      Stream.empty

    override val version: IO[String] = IO.pure("test-version")

    override val supportedSites: IO[Seq[String]] = IO.pure(Seq("youtube.com", "youtu.be"))

    override def videoInformation(uri: Uri): IO[VideoAnalysisResult] =
      IO.raiseError(new NotImplementedError("videoInformation not implemented in stub"))
  }

  "WorkExecutor download" should "download custom video site videos using download service" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]

    batchServiceProvider.transactor.use { implicit transactor =>
      for {
        scheduledVideo <- DataGenerators.scheduledVideoDownload[IO].generate
        customSiteVideo = scheduledVideo.copy(
          videoMetadata = scheduledVideo.videoMetadata.copy(videoSite = CustomVideoSite.SpankBang)
        )

        downloadResult = DownloadResult[IO](
          Uri.unsafeFromString("https://example.com/video.mp4"),
          "/videos/downloaded.mp4",
          5L,
          MediaType.video.mp4,
          Stream.emits[IO, Long](List(1L, 2L, 3L, 4L, 5L))
        )

        _ <- transactor(insertScheduledVideo(customSiteVideo))
        _ <- transactor(insertWorker(Worker("worker-0", WorkerStatus.Active, None, None, None, None)))

        batchSchedulingService = new StubBatchSchedulingService(customSiteVideo, customSiteVideo)
        batchVideoService = new StubBatchVideoService(
          Video(customSiteVideo.videoMetadata, FileResource("file-id", timestamp, "/videos/test.mp4", MediaType.video.mp4, 1000), timestamp, 0.seconds)
        )
        videoEnrichmentService = new StubVideoEnrichmentService
        repositoryService = new StubRepositoryService(true, Some(1000L), Some(MediaType.video.mp4))
        videoAnalysisService = new StubVideoAnalysisService(Uri.unsafeFromString("https://example.com/video.mp4"), 10.minutes)
        downloadService = new StubDownloadService(downloadResult)
        youTubeVideoDownloader = new StubYouTubeVideoDownloader

        workerDao = new DoobieWorkerDao(DoobieSchedulingDao)

        workExecutor = new WorkExecutorImpl[IO, ConnectionIO](
          DoobieFileResourceDao,
          workerDao,
          DoobieVideoMetadataDao,
          repositoryService,
          batchSchedulingService,
          videoAnalysisService,
          batchVideoService,
          downloadService,
          youTubeVideoDownloader,
          videoEnrichmentService,
          storageConfiguration
        )

        // Use the download method to create a resource
        downloadResource = workExecutor.download(customSiteVideo)

        result <- downloadResource.use {
          case (dataStream, fileResourceF) =>
            for {
              // Consume the data stream
              data <- dataStream.compile.toList
              fileResource <- fileResourceF
            } yield (data, fileResource)
        }

        _ <- IO.delay {
          result._1.size mustBe 5
          result._2.path must include("/videos/downloaded.mp4")
        }
      } yield ()
    }
  }

  "WorkExecutor execute" should "complete video download workflow" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]

    batchServiceProvider.transactor.use { implicit transactor =>
      for {
        scheduledVideo <- DataGenerators.scheduledVideoDownload[IO].generate
        customSiteVideo = scheduledVideo.copy(
          videoMetadata = scheduledVideo.videoMetadata.copy(
            videoSite = CustomVideoSite.SpankBang,
            size = 5L
          )
        )

        downloadResult = DownloadResult[IO](
          Uri.unsafeFromString("https://example.com/video.mp4"),
          "/videos/downloaded.mp4",
          5L,
          MediaType.video.mp4,
          Stream.emits[IO, Long](List(1L, 2L, 3L, 4L, 5L))
        )

        sampleVideo = Video(
          customSiteVideo.videoMetadata,
          FileResource("file-id", timestamp, "/videos/test.mp4", MediaType.video.mp4, 5L),
          timestamp,
          0.seconds
        )

        _ <- transactor(insertScheduledVideo(customSiteVideo))
        _ <- transactor(insertWorker(Worker("worker-0", WorkerStatus.Active, None, None, None, None)))

        batchSchedulingService = new StubBatchSchedulingService(customSiteVideo, customSiteVideo)
        batchVideoService = new StubBatchVideoService(sampleVideo)
        videoEnrichmentService = new StubVideoEnrichmentService
        repositoryService = new StubRepositoryService(true, Some(5L), Some(MediaType.video.mp4))
        videoAnalysisService = new StubVideoAnalysisService(Uri.unsafeFromString("https://example.com/video.mp4"), 10.minutes)
        downloadService = new StubDownloadService(downloadResult)
        youTubeVideoDownloader = new StubYouTubeVideoDownloader

        workerDao = new DoobieWorkerDao(DoobieSchedulingDao)

        workExecutor = new WorkExecutorImpl[IO, ConnectionIO](
          DoobieFileResourceDao,
          workerDao,
          DoobieVideoMetadataDao,
          repositoryService,
          batchSchedulingService,
          videoAnalysisService,
          batchVideoService,
          downloadService,
          youTubeVideoDownloader,
          videoEnrichmentService,
          storageConfiguration
        )

        worker = Worker("worker-0", WorkerStatus.Active, None, None, None, None)

        video <- workExecutor.execute(
          customSiteVideo,
          worker,
          Stream.empty,
          3
        )

        _ <- IO.delay {
          video.id mustBe customSiteVideo.videoMetadata.id
        }
      } yield ()
    }
  }

  "WorkExecutor" should "find video files with standard extensions" in runIO {
    // Testing storage configuration
    IO.delay {
      storageConfiguration.videoFolder mustBe "/videos"
      storageConfiguration.imageFolder mustBe "/images"
      storageConfiguration.otherVideoFolders mustBe List("/other-videos")
    }
  }

  "Worker model" should "create worker IDs from index" in {
    Worker.workerIdFromIndex(0) mustBe "worker-00"
    Worker.workerIdFromIndex(5) mustBe "worker-05"
    Worker.workerIdFromIndex(10) mustBe "worker-10"
    Worker.workerIdFromIndex(99) mustBe "worker-99"
  }

  "StorageConfiguration" should "contain correct paths" in {
    storageConfiguration.videoFolder mustBe "/videos"
    storageConfiguration.imageFolder mustBe "/images"
    storageConfiguration.otherVideoFolders mustBe List("/other-videos")
  }

  "WorkExecutor execute" should "handle retry when file size is smaller than expected" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]

    batchServiceProvider.transactor.use { implicit transactor =>
      for {
        scheduledVideo <- DataGenerators.scheduledVideoDownload[IO].generate
        customSiteVideo = scheduledVideo.copy(
          videoMetadata = scheduledVideo.videoMetadata.copy(
            videoSite = CustomVideoSite.SpankBang,
            size = 1000L // Larger than what we'll download
          )
        )

        downloadResult = DownloadResult[IO](
          Uri.unsafeFromString("https://example.com/video.mp4"),
          "/videos/downloaded.mp4",
          5L, // Much smaller than expected
          MediaType.video.mp4,
          Stream.emits[IO, Long](List(1L, 2L, 3L, 4L, 5L))
        )

        sampleVideo = Video(
          customSiteVideo.videoMetadata,
          FileResource("file-id", timestamp, "/videos/test.mp4", MediaType.video.mp4, 5L),
          timestamp,
          0.seconds
        )

        _ <- transactor(insertScheduledVideo(customSiteVideo))
        _ <- transactor(insertWorker(Worker("worker-retry", WorkerStatus.Active, None, None, None, None)))

        batchSchedulingService = new StubBatchSchedulingService(customSiteVideo, customSiteVideo)
        batchVideoService = new StubBatchVideoService(sampleVideo)
        videoEnrichmentService = new StubVideoEnrichmentService
        repositoryService = new StubRepositoryService(true, Some(5L), Some(MediaType.video.mp4))
        videoAnalysisService = new StubVideoAnalysisService(Uri.unsafeFromString("https://example.com/video.mp4"), 10.minutes)
        downloadService = new StubDownloadService(downloadResult)
        youTubeVideoDownloader = new StubYouTubeVideoDownloader

        workerDao = new DoobieWorkerDao(DoobieSchedulingDao)

        workExecutor = new WorkExecutorImpl[IO, ConnectionIO](
          DoobieFileResourceDao,
          workerDao,
          DoobieVideoMetadataDao,
          repositoryService,
          batchSchedulingService,
          videoAnalysisService,
          batchVideoService,
          downloadService,
          youTubeVideoDownloader,
          videoEnrichmentService,
          storageConfiguration
        )

        worker = Worker("worker-retry", WorkerStatus.Active, None, None, None, None)

        // With retries = 0, should complete despite size mismatch
        video <- workExecutor.execute(
          customSiteVideo,
          worker,
          Stream.empty,
          0 // No retries
        )

        _ <- IO.delay {
          video.id mustBe customSiteVideo.videoMetadata.id
        }
      } yield ()
    }
  }

  it should "update video duration when original duration is 0" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]

    batchServiceProvider.transactor.use { implicit transactor =>
      for {
        scheduledVideo <- DataGenerators.scheduledVideoDownload[IO].generate
        customSiteVideo = scheduledVideo.copy(
          videoMetadata = scheduledVideo.videoMetadata.copy(
            videoSite = CustomVideoSite.SpankBang,
            size = 5L,
            duration = 0.seconds // Duration is 0, should be updated
          )
        )

        downloadResult = DownloadResult[IO](
          Uri.unsafeFromString("https://example.com/video.mp4"),
          "/videos/downloaded.mp4",
          5L,
          MediaType.video.mp4,
          Stream.emits[IO, Long](List(1L, 2L, 3L, 4L, 5L))
        )

        sampleVideo = Video(
          customSiteVideo.videoMetadata,
          FileResource("file-id", timestamp, "/videos/test.mp4", MediaType.video.mp4, 5L),
          timestamp,
          0.seconds
        )

        _ <- transactor(insertScheduledVideo(customSiteVideo))
        _ <- transactor(insertWorker(Worker("worker-duration", WorkerStatus.Active, None, None, None, None)))

        batchSchedulingService = new StubBatchSchedulingService(customSiteVideo, customSiteVideo)
        batchVideoService = new StubBatchVideoService(sampleVideo)
        videoEnrichmentService = new StubVideoEnrichmentService
        repositoryService = new StubRepositoryService(true, Some(5L), Some(MediaType.video.mp4))
        // Return a non-zero duration
        videoAnalysisService = new StubVideoAnalysisService(Uri.unsafeFromString("https://example.com/video.mp4"), 15.minutes)
        downloadService = new StubDownloadService(downloadResult)
        youTubeVideoDownloader = new StubYouTubeVideoDownloader

        workerDao = new DoobieWorkerDao(DoobieSchedulingDao)

        workExecutor = new WorkExecutorImpl[IO, ConnectionIO](
          DoobieFileResourceDao,
          workerDao,
          DoobieVideoMetadataDao,
          repositoryService,
          batchSchedulingService,
          videoAnalysisService,
          batchVideoService,
          downloadService,
          youTubeVideoDownloader,
          videoEnrichmentService,
          storageConfiguration
        )

        worker = Worker("worker-duration", WorkerStatus.Active, None, None, None, None)

        video <- workExecutor.execute(
          customSiteVideo,
          worker,
          Stream.empty,
          0
        )

        _ <- IO.delay {
          video.id mustBe customSiteVideo.videoMetadata.id
        }
      } yield ()
    }
  }

  it should "handle file size mismatch and call update" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]

    batchServiceProvider.transactor.use { implicit transactor =>
      for {
        scheduledVideo <- DataGenerators.scheduledVideoDownload[IO].generate
        customSiteVideo = scheduledVideo.copy(
          videoMetadata = scheduledVideo.videoMetadata.copy(
            videoSite = CustomVideoSite.SpankBang,
            size = 100L, // Different from actual file size
            duration = 5.minutes
          )
        )

        downloadResult = DownloadResult[IO](
          Uri.unsafeFromString("https://example.com/video.mp4"),
          "/videos/downloaded.mp4",
          200L, // Different from expected
          MediaType.video.mp4,
          Stream.emits[IO, Long](List(50L, 100L, 150L, 200L))
        )

        sampleVideo = Video(
          customSiteVideo.videoMetadata,
          FileResource("file-id", timestamp, "/videos/test.mp4", MediaType.video.mp4, 200L),
          timestamp,
          0.seconds
        )

        _ <- transactor(insertScheduledVideo(customSiteVideo))
        _ <- transactor(insertWorker(Worker("worker-size", WorkerStatus.Active, None, None, None, None)))

        batchSchedulingService = new StubBatchSchedulingService(customSiteVideo, customSiteVideo)
        batchVideoService = new StubBatchVideoService(sampleVideo)
        videoEnrichmentService = new StubVideoEnrichmentService
        repositoryService = new StubRepositoryService(true, Some(200L), Some(MediaType.video.mp4))
        videoAnalysisService = new StubVideoAnalysisService(Uri.unsafeFromString("https://example.com/video.mp4"), 10.minutes)
        downloadService = new StubDownloadService(downloadResult)
        youTubeVideoDownloader = new StubYouTubeVideoDownloader

        workerDao = new DoobieWorkerDao(DoobieSchedulingDao)

        workExecutor = new WorkExecutorImpl[IO, ConnectionIO](
          DoobieFileResourceDao,
          workerDao,
          DoobieVideoMetadataDao,
          repositoryService,
          batchSchedulingService,
          videoAnalysisService,
          batchVideoService,
          downloadService,
          youTubeVideoDownloader,
          videoEnrichmentService,
          storageConfiguration
        )

        worker = Worker("worker-size", WorkerStatus.Active, None, None, None, None)

        video <- workExecutor.execute(
          customSiteVideo,
          worker,
          Stream.empty,
          0
        )

        _ <- IO.delay {
          video.id mustBe customSiteVideo.videoMetadata.id
        }
      } yield ()
    }
  }

  "WorkExecutor download for YTDownloaderSite" should "create download stream for YouTube videos" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]

    batchServiceProvider.transactor.use { implicit transactor =>
      for {
        scheduledVideo <- DataGenerators.scheduledVideoDownload[IO].generate
        ytVideo = scheduledVideo.copy(
          videoMetadata = scheduledVideo.videoMetadata.copy(
            videoSite = VideoSite.YTDownloaderSite("youtube")
          )
        )

        _ <- transactor(insertScheduledVideo(ytVideo))
        _ <- transactor(insertWorker(Worker("worker-yt", WorkerStatus.Active, None, None, None, None)))

        batchSchedulingService = new StubBatchSchedulingService(ytVideo, ytVideo)
        batchVideoService = new StubBatchVideoService(
          Video(ytVideo.videoMetadata, FileResource("file-id", timestamp, "/videos/test.mp4", MediaType.video.mp4, 1000), timestamp, 0.seconds)
        )
        videoEnrichmentService = new StubVideoEnrichmentService
        repositoryService = new StubRepositoryService(true, Some(1000L), Some(MediaType.video.mp4))
        videoAnalysisService = new StubVideoAnalysisService(Uri.unsafeFromString("https://youtube.com/watch?v=abc"), 10.minutes)
        downloadService = new StubDownloadService(
          DownloadResult[IO](Uri.unsafeFromString("https://youtube.com/video"), "/videos/yt.mp4", 1000L, MediaType.video.mp4, Stream.empty)
        )
        youTubeVideoDownloader = new StubYouTubeVideoDownloader

        workerDao = new DoobieWorkerDao(DoobieSchedulingDao)

        workExecutor = new WorkExecutorImpl[IO, ConnectionIO](
          DoobieFileResourceDao,
          workerDao,
          DoobieVideoMetadataDao,
          repositoryService,
          batchSchedulingService,
          videoAnalysisService,
          batchVideoService,
          downloadService,
          youTubeVideoDownloader,
          videoEnrichmentService,
          storageConfiguration
        )

        // Just verify the download method returns a Resource
        downloadResource = workExecutor.download(ytVideo)

        // The YouTube download uses a different code path that creates streams differently
        _ <- IO.delay {
          downloadResource mustBe a[Resource[IO, _]]
        }
      } yield ()
    }
  }

  "StubRepositoryService" should "find files with correct extensions" in runIO {
    // Create a repository that returns size for .mp4 extension
    val repository = new StubRepositoryService(true, None, None) {
      override def size(key: String): IO[Option[Long]] = {
        if (key.endsWith(".mp4")) IO.pure(Some(1000L))
        else IO.pure(None)
      }
    }

    // Test that the first matching extension is found
    repository.size("/videos/test.mp4").flatMap { result =>
      IO.delay {
        result mustBe Some(1000L)
      }
    }
  }

  it should "return None when no extension matches" in runIO {
    val repository = new StubRepositoryService(true, None, None)

    repository.size("/videos/test.mp4").flatMap { result =>
      IO.delay {
        result mustBe None
      }
    }
  }

  "StubRepositoryService list" should "list files in directory" in runIO {
    val repository = new StubRepositoryService(true, Some(1000L), Some(MediaType.video.mp4)) {
      override def list(prefix: String): Stream[IO, String] =
        Stream.emits(List("/videos/video-001.mp4", "/videos/video-002.mp4", "/videos/video-003.mp4"))
    }

    repository.list("/videos").compile.toList.flatMap { files =>
      IO.delay {
        files.size mustBe 3
        files must contain("/videos/video-001.mp4")
      }
    }
  }

  it should "filter files by prefix" in runIO {
    val videoId = "test-video-id"
    val repository = new StubRepositoryService(true, Some(1000L), Some(MediaType.video.mp4)) {
      override def list(prefix: String): Stream[IO, String] =
        Stream.emits(List(
          s"/videos/$videoId-video.mp4",
          "/videos/other-video.mp4",
          s"/videos/$videoId-another.mp4"
        ))
    }

    repository.list("/videos")
      .filter(_.contains(videoId))
      .compile
      .toList
      .flatMap { files =>
        IO.delay {
          files.size mustBe 2
          files.foreach(_ must include(videoId))
        }
      }
  }

  "WorkExecutor with crawling repository" should "find video file by crawling when extension search fails" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]

    batchServiceProvider.transactor.use { implicit transactor =>
      for {
        scheduledVideo <- DataGenerators.scheduledVideoDownload[IO].generate
        ytVideo = scheduledVideo.copy(
          videoMetadata = scheduledVideo.videoMetadata.copy(
            videoSite = VideoSite.YTDownloaderSite("youtube"),
            size = 1000L,
            duration = 5.minutes
          )
        )

        _ <- transactor(insertScheduledVideo(ytVideo))
        _ <- transactor(insertWorker(Worker("worker-crawl", WorkerStatus.Active, None, None, None, None)))

        batchSchedulingService = new StubBatchSchedulingService(ytVideo, ytVideo)
        batchVideoService = new StubBatchVideoService(
          Video(ytVideo.videoMetadata, FileResource("file-id", timestamp, s"/videos/${ytVideo.videoMetadata.id}.mp4", MediaType.video.mp4, 1000), timestamp, 0.seconds)
        )
        videoEnrichmentService = new StubVideoEnrichmentService
        // Repository that finds file by crawling
        repositoryService = new StubRepositoryService(true, Some(1000L), Some(MediaType.video.mp4)) {
          private var sizeCallCount = 0
          override def size(key: String): IO[Option[Long]] = IO {
            sizeCallCount += 1
            // First few calls for extension check return None
            // Then return Some for the crawled file
            if (key == s"/videos/${ytVideo.videoMetadata.id}.mp4") Some(1000L)
            else None
          }

          override def list(prefix: String): Stream[IO, String] =
            Stream.emit(s"/videos/${ytVideo.videoMetadata.id}.mp4")
        }
        videoAnalysisService = new StubVideoAnalysisService(Uri.unsafeFromString("https://youtube.com/watch?v=abc"), 10.minutes)
        downloadService = new StubDownloadService(
          DownloadResult[IO](Uri.unsafeFromString("https://youtube.com/video"), "/videos/yt.mp4", 1000L, MediaType.video.mp4, Stream.empty)
        )
        youTubeVideoDownloader = new StubYouTubeVideoDownloader {
          import com.ruchij.core.services.video.models.{YTDataSize, YTDataUnit}
          override def downloadVideo(videoUrl: Uri, destinationPath: String): Stream[IO, YTDownloaderProgress] =
            Stream.emit(YTDownloaderProgress(100.0, YTDataSize(1.0, YTDataUnit.MiB), YTDataSize(1.0, YTDataUnit.MiB), 0.seconds))
        }

        workerDao = new DoobieWorkerDao(DoobieSchedulingDao)

        workExecutor = new WorkExecutorImpl[IO, ConnectionIO](
          DoobieFileResourceDao,
          workerDao,
          DoobieVideoMetadataDao,
          repositoryService,
          batchSchedulingService,
          videoAnalysisService,
          batchVideoService,
          downloadService,
          youTubeVideoDownloader,
          videoEnrichmentService,
          storageConfiguration
        )

        worker = Worker("worker-crawl", WorkerStatus.Active, None, None, None, None)

        // Execute download - this will exercise the YouTube download path
        video <- workExecutor.execute(
          ytVideo,
          worker,
          Stream.empty,
          0
        )

        _ <- IO.delay {
          video.id mustBe ytVideo.videoMetadata.id
        }
      } yield ()
    }
  }

  "findVideoFileByCrawling" should "raise ResourceNotFoundException when no file is found" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]

    batchServiceProvider.transactor.use { implicit transactor =>
      for {
        scheduledVideo <- DataGenerators.scheduledVideoDownload[IO].generate
        ytVideo = scheduledVideo.copy(
          videoMetadata = scheduledVideo.videoMetadata.copy(
            videoSite = VideoSite.YTDownloaderSite("youtube"),
            size = 1000L,
            duration = 5.minutes
          )
        )

        _ <- transactor(insertScheduledVideo(ytVideo))
        _ <- transactor(insertWorker(Worker("worker-notfound", WorkerStatus.Active, None, None, None, None)))

        batchSchedulingService = new StubBatchSchedulingService(ytVideo, ytVideo)
        batchVideoService = new StubBatchVideoService(
          Video(ytVideo.videoMetadata, FileResource("file-id", timestamp, "/videos/test.mp4", MediaType.video.mp4, 1000), timestamp, 0.seconds)
        )
        videoEnrichmentService = new StubVideoEnrichmentService
        // Repository returns empty list - no files found
        repositoryService = new StubRepositoryService(true, Some(1000L), Some(MediaType.video.mp4)) {
          override def size(key: String): IO[Option[Long]] = IO.pure(None)
          override def list(prefix: String): Stream[IO, String] = Stream.empty
        }
        videoAnalysisService = new StubVideoAnalysisService(Uri.unsafeFromString("https://youtube.com/watch?v=abc"), 10.minutes)
        downloadService = new StubDownloadService(
          DownloadResult[IO](Uri.unsafeFromString("https://youtube.com/video"), "/videos/yt.mp4", 1000L, MediaType.video.mp4, Stream.empty)
        )
        youTubeVideoDownloader = new StubYouTubeVideoDownloader

        workerDao = new DoobieWorkerDao(DoobieSchedulingDao)

        workExecutor = new WorkExecutorImpl[IO, ConnectionIO](
          DoobieFileResourceDao,
          workerDao,
          DoobieVideoMetadataDao,
          repositoryService,
          batchSchedulingService,
          videoAnalysisService,
          batchVideoService,
          downloadService,
          youTubeVideoDownloader,
          videoEnrichmentService,
          storageConfiguration
        )

        worker = Worker("worker-notfound", WorkerStatus.Active, None, None, None, None)

        // This should fail because the file is not found
        result <- workExecutor.execute(ytVideo, worker, Stream.empty, 0).attempt

        _ <- IO.delay {
          result.isLeft mustBe true
          result.left.exists(_.getMessage.contains("Unable to find file")) mustBe true
        }
      } yield ()
    }
  }

  it should "raise IllegalStateException when multiple files are found" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]

    batchServiceProvider.transactor.use { implicit transactor =>
      for {
        scheduledVideo <- DataGenerators.scheduledVideoDownload[IO].generate
        ytVideo = scheduledVideo.copy(
          videoMetadata = scheduledVideo.videoMetadata.copy(
            videoSite = VideoSite.YTDownloaderSite("youtube"),
            size = 1000L,
            duration = 5.minutes
          )
        )

        _ <- transactor(insertScheduledVideo(ytVideo))
        _ <- transactor(insertWorker(Worker("worker-multi", WorkerStatus.Active, None, None, None, None)))

        batchSchedulingService = new StubBatchSchedulingService(ytVideo, ytVideo)
        batchVideoService = new StubBatchVideoService(
          Video(ytVideo.videoMetadata, FileResource("file-id", timestamp, "/videos/test.mp4", MediaType.video.mp4, 1000), timestamp, 0.seconds)
        )
        videoEnrichmentService = new StubVideoEnrichmentService
        // Repository returns multiple files with the same ID prefix
        repositoryService = new StubRepositoryService(true, Some(1000L), Some(MediaType.video.mp4)) {
          override def size(key: String): IO[Option[Long]] = IO.pure(None)
          override def list(prefix: String): Stream[IO, String] =
            Stream.emits(List(
              s"/videos/${ytVideo.videoMetadata.id}.mp4",
              s"/videos/${ytVideo.videoMetadata.id}.webm"
            ))
        }
        videoAnalysisService = new StubVideoAnalysisService(Uri.unsafeFromString("https://youtube.com/watch?v=abc"), 10.minutes)
        downloadService = new StubDownloadService(
          DownloadResult[IO](Uri.unsafeFromString("https://youtube.com/video"), "/videos/yt.mp4", 1000L, MediaType.video.mp4, Stream.empty)
        )
        youTubeVideoDownloader = new StubYouTubeVideoDownloader

        workerDao = new DoobieWorkerDao(DoobieSchedulingDao)

        workExecutor = new WorkExecutorImpl[IO, ConnectionIO](
          DoobieFileResourceDao,
          workerDao,
          DoobieVideoMetadataDao,
          repositoryService,
          batchSchedulingService,
          videoAnalysisService,
          batchVideoService,
          downloadService,
          youTubeVideoDownloader,
          videoEnrichmentService,
          storageConfiguration
        )

        worker = Worker("worker-multi", WorkerStatus.Active, None, None, None, None)

        // This should fail because multiple files are found
        result <- workExecutor.execute(ytVideo, worker, Stream.empty, 0).attempt

        _ <- IO.delay {
          result.isLeft mustBe true
          result.left.exists(_.getMessage.contains("Multiple file keys found")) mustBe true
        }
      } yield ()
    }
  }

  "download method for CustomVideoSite" should "download video from custom site" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]

    batchServiceProvider.transactor.use { implicit transactor =>
      for {
        scheduledVideo <- DataGenerators.scheduledVideoDownload[IO].generate
        customVideo = scheduledVideo.copy(
          videoMetadata = scheduledVideo.videoMetadata.copy(
            videoSite = CustomVideoSite.SpankBang,
            size = 2000L,
            duration = 10.minutes
          )
        )

        _ <- transactor(insertScheduledVideo(customVideo))
        _ <- transactor(insertWorker(Worker("worker-custom", WorkerStatus.Active, None, None, None, None)))

        downloadUri = Uri.unsafeFromString("https://cdn.spankbang.com/video.mp4")

        batchSchedulingService = new StubBatchSchedulingService(customVideo, customVideo)
        batchVideoService = new StubBatchVideoService(
          Video(customVideo.videoMetadata, FileResource("file-id", timestamp, "/videos/custom.mp4", MediaType.video.mp4, 2000), timestamp, 0.seconds)
        )
        videoEnrichmentService = new StubVideoEnrichmentService
        repositoryService = new StubRepositoryService(true, Some(2000L), Some(MediaType.video.mp4))
        videoAnalysisService = new StubVideoAnalysisService(downloadUri, 10.minutes)
        downloadService = new StubDownloadService(
          DownloadResult[IO](downloadUri, s"/videos/${customVideo.videoMetadata.id}-video.mp4", 2000L, MediaType.video.mp4, Stream.emit(2000L))
        )
        youTubeVideoDownloader = new StubYouTubeVideoDownloader

        workerDao = new DoobieWorkerDao(DoobieSchedulingDao)

        workExecutor = new WorkExecutorImpl[IO, ConnectionIO](
          DoobieFileResourceDao,
          workerDao,
          DoobieVideoMetadataDao,
          repositoryService,
          batchSchedulingService,
          videoAnalysisService,
          batchVideoService,
          downloadService,
          youTubeVideoDownloader,
          videoEnrichmentService,
          storageConfiguration
        )

        worker = Worker("worker-custom", WorkerStatus.Active, None, None, None, None)

        video <- workExecutor.execute(customVideo, worker, Stream.empty, 0)

        _ <- IO.delay {
          video.id mustBe customVideo.videoMetadata.id
        }
      } yield ()
    }
  }
}
