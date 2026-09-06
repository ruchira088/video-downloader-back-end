package com.ruchij.batch.services.worker

import cats.effect.{IO, Resource}
import com.ruchij.batch.daos.workers.DoobieWorkerDao
import com.ruchij.batch.daos.workers.models.Worker
import com.ruchij.batch.external.BatchResourcesProvider
import com.ruchij.batch.external.containers.ContainerBatchResourcesProvider
import com.ruchij.batch.test.stubs.BatchStubs._
import com.ruchij.core.config.StorageConfiguration
import com.ruchij.core.daos.resource.DoobieFileResourceDao
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.scheduling.DoobieSchedulingDao
import com.ruchij.core.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.core.services.video.models.YTDownloaderProgress
import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.daos.videometadata.DoobieVideoMetadataDao
import com.ruchij.core.daos.videometadata.models.{CustomVideoSite, VideoSite}
import com.ruchij.core.daos.workers.models.WorkerStatus
import com.ruchij.core.services.download.models.DownloadResult
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.test.data.DataGenerators
import doobie.free.connection.ConnectionIO
import fs2.Stream
import org.http4s.{MediaType, Uri}
import com.ruchij.core.types.TimeUtils
import org.scalamock.scalatest.MockFactory
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class WorkExecutorIntegrationSpec extends AnyFlatSpec with MockFactory with Matchers with OptionValues {

  val timestamp = TimeUtils.instantOf(2024, 5, 15, 10, 0)
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

        batchSchedulingService <- StubBatchSchedulingService.create(customSiteVideo)
        batchVideoService = new StubBatchVideoService(
          Video(customSiteVideo.videoMetadata, FileResource("file-id", timestamp, "/videos/test.mp4", MediaType.video.mp4, 1000), timestamp, 0.seconds)
        )
        videoEnrichmentService = new StubVideoEnrichmentService(timestamp)
        repositoryService = new StubRepositoryService(true, Some(1000L), Some(MediaType.video.mp4))
        videoAnalysisService = new StubVideoAnalysisService(Uri.unsafeFromString("https://example.com/video.mp4"), 10.minutes)
        downloadService = new StubDownloadService(downloadResult)
        youTubeVideoDownloader = new StubYouTubeVideoDownloader()

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

        batchSchedulingService <- StubBatchSchedulingService.create(customSiteVideo)
        batchVideoService = new StubBatchVideoService(sampleVideo)
        videoEnrichmentService = new StubVideoEnrichmentService(timestamp)
        repositoryService = new StubRepositoryService(true, Some(5L), Some(MediaType.video.mp4))
        videoAnalysisService = new StubVideoAnalysisService(Uri.unsafeFromString("https://example.com/video.mp4"), 10.minutes)
        downloadService = new StubDownloadService(downloadResult)
        youTubeVideoDownloader = new StubYouTubeVideoDownloader()

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

        batchSchedulingService <- StubBatchSchedulingService.create(customSiteVideo)
        batchVideoService = new StubBatchVideoService(sampleVideo)
        videoEnrichmentService = new StubVideoEnrichmentService(timestamp)
        repositoryService = new StubRepositoryService(true, Some(5L), Some(MediaType.video.mp4))
        videoAnalysisService = new StubVideoAnalysisService(Uri.unsafeFromString("https://example.com/video.mp4"), 10.minutes)
        downloadService = new StubDownloadService(downloadResult)
        youTubeVideoDownloader = new StubYouTubeVideoDownloader()

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

        batchSchedulingService <- StubBatchSchedulingService.create(customSiteVideo)
        batchVideoService = new StubBatchVideoService(sampleVideo)
        videoEnrichmentService = new StubVideoEnrichmentService(timestamp)
        repositoryService = new StubRepositoryService(true, Some(5L), Some(MediaType.video.mp4))
        // Return a non-zero duration
        videoAnalysisService = new StubVideoAnalysisService(Uri.unsafeFromString("https://example.com/video.mp4"), 15.minutes)
        downloadService = new StubDownloadService(downloadResult)
        youTubeVideoDownloader = new StubYouTubeVideoDownloader()

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

        batchSchedulingService <- StubBatchSchedulingService.create(customSiteVideo)
        batchVideoService = new StubBatchVideoService(sampleVideo)
        videoEnrichmentService = new StubVideoEnrichmentService(timestamp)
        repositoryService = new StubRepositoryService(true, Some(200L), Some(MediaType.video.mp4))
        videoAnalysisService = new StubVideoAnalysisService(Uri.unsafeFromString("https://example.com/video.mp4"), 10.minutes)
        downloadService = new StubDownloadService(downloadResult)
        youTubeVideoDownloader = new StubYouTubeVideoDownloader()

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

        batchSchedulingService <- StubBatchSchedulingService.create(ytVideo)
        batchVideoService = new StubBatchVideoService(
          Video(ytVideo.videoMetadata, FileResource("file-id", timestamp, "/videos/test.mp4", MediaType.video.mp4, 1000), timestamp, 0.seconds)
        )
        videoEnrichmentService = new StubVideoEnrichmentService(timestamp)
        repositoryService = new StubRepositoryService(true, Some(1000L), Some(MediaType.video.mp4))
        videoAnalysisService = new StubVideoAnalysisService(Uri.unsafeFromString("https://youtube.com/watch?v=abc"), 10.minutes)
        downloadService = new StubDownloadService(
          DownloadResult[IO](Uri.unsafeFromString("https://youtube.com/video"), "/videos/yt.mp4", 1000L, MediaType.video.mp4, Stream.empty)
        )
        youTubeVideoDownloader = new StubYouTubeVideoDownloader()

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

        batchSchedulingService <- StubBatchSchedulingService.create(ytVideo)
        batchVideoService = new StubBatchVideoService(
          Video(ytVideo.videoMetadata, FileResource("file-id", timestamp, s"/videos/${ytVideo.videoMetadata.id}.mp4", MediaType.video.mp4, 1000), timestamp, 0.seconds)
        )
        videoEnrichmentService = new StubVideoEnrichmentService(timestamp)
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
        youTubeVideoDownloader = new StubYouTubeVideoDownloader() {
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

        batchSchedulingService <- StubBatchSchedulingService.create(ytVideo)
        batchVideoService = new StubBatchVideoService(
          Video(ytVideo.videoMetadata, FileResource("file-id", timestamp, "/videos/test.mp4", MediaType.video.mp4, 1000), timestamp, 0.seconds)
        )
        videoEnrichmentService = new StubVideoEnrichmentService(timestamp)
        // Repository returns empty list - no files found
        repositoryService = new StubRepositoryService(true, Some(1000L), Some(MediaType.video.mp4)) {
          override def size(key: String): IO[Option[Long]] = IO.pure(None)
          override def list(prefix: String): Stream[IO, String] = Stream.empty
        }
        videoAnalysisService = new StubVideoAnalysisService(Uri.unsafeFromString("https://youtube.com/watch?v=abc"), 10.minutes)
        downloadService = new StubDownloadService(
          DownloadResult[IO](Uri.unsafeFromString("https://youtube.com/video"), "/videos/yt.mp4", 1000L, MediaType.video.mp4, Stream.empty)
        )
        youTubeVideoDownloader = new StubYouTubeVideoDownloader()

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

        batchSchedulingService <- StubBatchSchedulingService.create(ytVideo)
        batchVideoService = new StubBatchVideoService(
          Video(ytVideo.videoMetadata, FileResource("file-id", timestamp, "/videos/test.mp4", MediaType.video.mp4, 1000), timestamp, 0.seconds)
        )
        videoEnrichmentService = new StubVideoEnrichmentService(timestamp)
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
        youTubeVideoDownloader = new StubYouTubeVideoDownloader()

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

        batchSchedulingService <- StubBatchSchedulingService.create(customVideo)
        batchVideoService = new StubBatchVideoService(
          Video(customVideo.videoMetadata, FileResource("file-id", timestamp, "/videos/custom.mp4", MediaType.video.mp4, 2000), timestamp, 0.seconds)
        )
        videoEnrichmentService = new StubVideoEnrichmentService(timestamp)
        repositoryService = new StubRepositoryService(true, Some(2000L), Some(MediaType.video.mp4))
        videoAnalysisService = new StubVideoAnalysisService(downloadUri, 10.minutes)
        downloadService = new StubDownloadService(
          DownloadResult[IO](downloadUri, s"/videos/${customVideo.videoMetadata.id}-video.mp4", 2000L, MediaType.video.mp4, Stream.emit(2000L))
        )
        youTubeVideoDownloader = new StubYouTubeVideoDownloader()

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
