package com.ruchij.batch.services.worker

import cats.arrow.FunctionK
import cats.effect.testkit.TestControl
import cats.effect.{IO, Ref}
import cats.~>
import com.ruchij.batch.daos.workers.WorkerDao
import com.ruchij.batch.daos.workers.models.Worker
import com.ruchij.batch.services.enrichment.VideoEnrichmentService
import com.ruchij.batch.services.scheduler.Scheduler.PausedVideoDownload
import com.ruchij.batch.test.stubs.BatchStubs._
import com.ruchij.core.config.StorageConfiguration
import com.ruchij.core.daos.resource.FileResourceDao
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.scheduling.models.SchedulingStatus
import com.ruchij.core.daos.snapshot.models.Snapshot
import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.daos.videometadata.VideoMetadataDao
import com.ruchij.core.daos.videometadata.models.{CustomVideoSite, VideoMetadata, VideoSite}
import com.ruchij.core.daos.workers.models.WorkerStatus
import com.ruchij.core.services.download.models.DownloadResult
import com.ruchij.core.services.video.models.{YTDataSize, YTDataUnit, YTDownloaderProgress}
import com.ruchij.core.test.IOSupport.{IOWrapper, runIO}
import com.ruchij.core.types.{Clock, TimeUtils}
import fs2.Stream
import org.http4s.{MediaType, Uri}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.time.Instant
import scala.concurrent.duration._

class WorkExecutorImplSpec extends AnyFlatSpec with Matchers {

  private val timestamp = TimeUtils.instantOf(2024, 5, 15, 10, 0)

  private val thumbnail = FileResource("thumbnail-id", timestamp, "/images/thumbnail.jpg", MediaType.image.jpeg, 100)

  private val videoMetadata =
    VideoMetadata(
      Uri.unsafeFromString("https://spankbang.com/video/abc"),
      "spankbang-abc",
      CustomVideoSite.SpankBang,
      "Test video",
      10.minutes,
      5_000,
      thumbnail
    )

  private val scheduledVideoDownload =
    com.ruchij.core.daos.scheduling.models.ScheduledVideoDownload(
      timestamp,
      timestamp,
      SchedulingStatus.Active,
      0,
      videoMetadata,
      None,
      None
    )

  private val video =
    Video(videoMetadata, FileResource("spankbang-abc", timestamp, "/videos/abc.mp4", MediaType.video.mp4, 5_000), timestamp, 0.seconds)

  private val worker = Worker("worker-00", WorkerStatus.Active, None, None, None, Some("test-owner"))

  private implicit val identityTransaction: IO ~> IO = FunctionK.id[IO]

  private implicit val clock: Clock[IO] = com.ruchij.core.test.Providers.stubClock[IO](timestamp)

  private class RecordingWorkerDao(val heartBeats: Ref[IO, List[(String, Instant)]]) extends WorkerDao[IO] {
    private def notImplemented[A]: IO[A] = IO.raiseError(new NotImplementedError("Not used by WorkExecutorImpl"))

    override val idleWorker: IO[Option[Worker]] = notImplemented
    override val all: IO[Seq[Worker]] = notImplemented
    override def insert(worker: Worker): IO[Int] = notImplemented
    override def getById(workerId: String): IO[Option[Worker]] = notImplemented
    override def setStatus(workerId: String, workerStatus: WorkerStatus): IO[Int] = notImplemented
    override def reserveWorker(workerId: String, owner: String, timestamp: Instant): IO[Option[Worker]] = notImplemented
    override def assignTask(workerId: String, scheduledVideoId: String, owner: String, timestamp: Instant): IO[Option[Worker]] =
      notImplemented
    override def releaseWorker(workerId: String, owner: String): IO[Option[Worker]] = notImplemented
    override def updateHeartBeat(workerId: String, owner: String, timestamp: Instant): IO[Option[Worker]] =
      heartBeats.update(_ :+ (owner -> timestamp)).as(Some(worker))
    override def cleanUpStaleWorkers(heartBeatBefore: Instant): IO[Seq[Worker]] = notImplemented
    override def updateWorkerStatuses(workerStatus: WorkerStatus): IO[Seq[Worker]] = notImplemented
    override def clearScheduledVideoDownload(scheduledVideoDownloadId: String): IO[Int] = notImplemented
  }

  private class NoOpFileResourceDao extends FileResourceDao[IO] {
    override def insert(resource: FileResource): IO[Int] = IO.pure(1)
    override def update(id: String, size: Long): IO[Int] = IO.pure(1)
    override def getById(id: String): IO[Option[FileResource]] = IO.pure(None)
    override def findByPath(path: String): IO[Option[FileResource]] = IO.pure(None)
    override def deleteById(id: String): IO[Int] = IO.pure(1)
  }

  private class NoOpVideoMetadataDao extends VideoMetadataDao[IO] {
    override def insert(videoMetadata: VideoMetadata): IO[Int] = IO.pure(1)
    override def update(videoMetadataId: String, title: Option[String], size: Option[Long], maybeDuration: Option[FiniteDuration]): IO[Int] =
      IO.pure(1)
    override def findById(videoMetadataId: String): IO[Option[VideoMetadata]] = IO.pure(None)
    override def isThumbnailFileResource(thumbnailId: String): IO[Boolean] = IO.pure(false)
    override def findByUrl(uri: Uri): IO[Option[VideoMetadata]] = IO.pure(None)
    override def deleteById(videoMetadataId: String): IO[Int] = IO.pure(1)
  }

  private def createWorkExecutor(
    downloadData: Stream[IO, Long],
    batchSchedulingService: StubBatchSchedulingService,
    workerDao: WorkerDao[IO],
    videoEnrichmentService: VideoEnrichmentService[IO] = new StubVideoEnrichmentService(timestamp),
    videoMetadataDao: VideoMetadataDao[IO] = new NoOpVideoMetadataDao,
    youTubeVideoDownloader: StubYouTubeVideoDownloader = new StubYouTubeVideoDownloader()
  ): WorkExecutorImpl[IO, IO] =
    new WorkExecutorImpl[IO, IO](
      new NoOpFileResourceDao,
      workerDao,
      videoMetadataDao,
      new StubRepositoryService(fileSize = Some(5_000), fileType = Some(MediaType.video.mp4)),
      batchSchedulingService,
      new StubVideoAnalysisService(Uri.unsafeFromString("https://cdn.spankbang.com/abc.mp4"), 10.minutes),
      new StubBatchVideoService(video),
      new StubDownloadService(
        DownloadResult[IO](
          Uri.unsafeFromString("https://cdn.spankbang.com/abc.mp4"),
          "/videos/abc.mp4",
          5_000,
          MediaType.video.mp4,
          downloadData
        )
      ),
      youTubeVideoDownloader,
      videoEnrichmentService,
      StorageConfiguration("/videos", "/images", List.empty)
    )

  "execute" should "fail with PausedVideoDownload and leave the download incomplete when the interrupt fires" in runIO {
    for {
      batchSchedulingService <- StubBatchSchedulingService.create(scheduledVideoDownload)
      heartBeats <- Ref.of[IO, List[(String, Instant)]](List.empty)

      // A download that would run forever if it were not interrupted
      neverEndingDownload = Stream.awakeEvery[IO](50.millis).zipWithIndex.map { case (_, index) => index + 1 }

      workExecutor = createWorkExecutor(neverEndingDownload, batchSchedulingService, new RecordingWorkerDao(heartBeats))

      pauseSignal: Stream[IO, Boolean] = Stream.sleep_[IO](300.millis) ++ Stream.emit(true)

      error <- workExecutor.execute(scheduledVideoDownload, worker, pauseSignal, retries = 0).withTimeout(10.seconds).error
      statusUpdates <- batchSchedulingService.statusUpdates.get
      completedIds <- batchSchedulingService.completedIds.get
    } yield {
      error mustBe PausedVideoDownload
      statusUpdates.map { case (_, status) => status } must not contain SchedulingStatus.Downloaded
      completedIds mustBe empty
    }
  }

  it should "mark the download as downloaded and complete it when the data stream finishes" in runIO {
    for {
      batchSchedulingService <- StubBatchSchedulingService.create(scheduledVideoDownload)
      heartBeats <- Ref.of[IO, List[(String, Instant)]](List.empty)

      workExecutor =
        createWorkExecutor(Stream.emits[IO, Long](List(1_000, 2_500, 5_000)), batchSchedulingService, new RecordingWorkerDao(heartBeats))

      result <- workExecutor.execute(scheduledVideoDownload, worker, Stream.empty, retries = 0).withTimeout(10.seconds)
      statusUpdates <- batchSchedulingService.statusUpdates.get
      completedIds <- batchSchedulingService.completedIds.get
      progressUpdates <- batchSchedulingService.progressUpdates.get
    } yield {
      result mustBe video
      statusUpdates mustBe List(videoMetadata.id -> SchedulingStatus.Downloaded)
      completedIds mustBe List(videoMetadata.id)
      progressUpdates.map(_.bytes) must contain(5_000L)
    }
  }

  /** Runs a download under TestControl and returns when, since the start, each download progress was published. */
  private def publishedProgress(downloadData: Stream[IO, Long]): IO[List[(FiniteDuration, Long)]] = {
    val test =
      for {
        timedProgressUpdates <- Ref.of[IO, List[(FiniteDuration, Long)]](List.empty)
        heartBeats <- Ref.of[IO, List[(String, Instant)]](List.empty)
        stubBatchSchedulingService <- StubBatchSchedulingService.create(scheduledVideoDownload)

        batchSchedulingService = new StubBatchSchedulingService(
          scheduledVideoDownload,
          stubBatchSchedulingService.statusUpdates,
          stubBatchSchedulingService.completedIds,
          stubBatchSchedulingService.progressUpdates
        ) {
          override def publishDownloadProgress(id: String, downloadedBytes: Long): IO[Unit] =
            IO.monotonic.flatMap(time => timedProgressUpdates.update(_ :+ (time -> downloadedBytes)))
        }

        workExecutor = createWorkExecutor(downloadData, batchSchedulingService, new RecordingWorkerDao(heartBeats))

        start <- IO.monotonic
        // fs2 interrupts the download when the interrupt stream ends, so it never ends, as the scheduler's does not
        _ <- workExecutor.execute(scheduledVideoDownload, worker, Stream.never[IO], retries = 0)
        published <- timedProgressUpdates.get
      } yield published.map { case (time, bytes) => (time - start) -> bytes }

    TestControl.executeEmbed(test)
  }

  private def largestGap(published: List[(FiniteDuration, Long)]): FiniteDuration = {
    val times = Duration.Zero :: published.map { case (time, _) => time }
    times.zip(times.drop(1)).map { case (previous, next) => next - previous }.max
  }

  it should "publish the download progress regularly while the downloader reports progress continuously" in runIO {
    // The HTTP download emits on every chunk and yt-dlp on every progress line, far more often than every 3 seconds
    val continuousDownload = Stream.awakeEvery[IO](200.millis).take(300).zipWithIndex.map { case (_, index) => (index + 1) * 10 }

    publishedProgress(continuousDownload).map { published =>
      // The first progress arrives 200 milliseconds in, so allow for that on top of the 3 second period
      largestGap(published) must be < 4.seconds
      published.map { case (_, bytes) => bytes }.last mustBe 5_000L
    }
  }

  it should "keep publishing the download progress while the downloader is quiet" in runIO {
    // yt-dlp goes quiet while it merges the downloaded formats, and a download can stall for a while. The published
    // progress refreshes last_updated_at, so without it a healthy download would be marked as stale after 2 minutes.
    val quietDownload = Stream.emit(1_000L) ++ Stream.sleep_[IO](3.minutes) ++ Stream.emit(5_000L)

    publishedProgress(quietDownload).map { published =>
      largestGap(published) must be <= 3.seconds
      published.map { case (_, bytes) => bytes }.distinct mustBe List(1_000L, 5_000L)
    }
  }

  "sampleLatest" should "emit the latest value on every tick and the last value when the stream ends" in runIO {
    val source = Stream.emits[IO, Int](List(1, 2)) ++ Stream.sleep_[IO](7.seconds) ++ Stream.emit(3)

    TestControl
      .executeEmbed(source.through(WorkExecutorImpl.sampleLatest(2.seconds, repeatUnchanged = true)).compile.toList)
      .map { _ mustBe List(2, 2, 2, 3) }
  }

  it should "only emit changed values when the unchanged values are not repeated" in runIO {
    val source = Stream.emits[IO, Int](List(1, 2)) ++ Stream.sleep_[IO](7.seconds) ++ Stream.emit(3)

    TestControl
      .executeEmbed(source.through(WorkExecutorImpl.sampleLatest(2.seconds, repeatUnchanged = false)).compile.toList)
      .map { _ mustBe List(2, 3) }
  }

  it should "fail when the stream fails" in runIO {
    val failure = new RuntimeException("Download failed")
    val source = Stream.emit[IO, Int](1) ++ Stream.sleep_[IO](5.seconds) ++ Stream.raiseError[IO](failure)

    TestControl
      .executeEmbed(source.through(WorkExecutorImpl.sampleLatest(2.seconds, repeatUnchanged = true)).compile.toList.attempt)
      .map { _ mustBe Left(failure) }
  }

  "execute" should "keep the worker heartbeat going after the download, while the snapshots are taken" in runIO {
    // Taking the snapshots of a large video takes minutes, long after the data stream has finished
    val slowVideoEnrichmentService =
      new StubVideoEnrichmentService(timestamp) {
        override def videoSnapshots(video: Video): IO[List[Snapshot]] = IO.sleep(3.minutes).as(List.empty)
      }

    val test =
      for {
        batchSchedulingService <- StubBatchSchedulingService.create(scheduledVideoDownload)
        heartBeats <- Ref.of[IO, List[(String, Instant)]](List.empty)

        workExecutor =
          createWorkExecutor(
            Stream.emits[IO, Long](List(1_000, 5_000)),
            batchSchedulingService,
            new RecordingWorkerDao(heartBeats),
            slowVideoEnrichmentService
          )

        result <- workExecutor.execute(scheduledVideoDownload, worker, Stream.empty, retries = 0)
        recordedHeartBeats <- heartBeats.get
      } yield (result, recordedHeartBeats)

    TestControl.executeEmbed(test).map {
      case (result, recordedHeartBeats) =>
        result mustBe video

        // A heartbeat every 30 seconds keeps the worker from being freed as stale by another instance
        recordedHeartBeats.size must be >= 5
        recordedHeartBeats.map { case (owner, _) => owner }.distinct mustBe List("test-owner")
    }
  }

  "execute" should "complete the download even if publishing the download progress fails" in runIO {
    for {
      stubBatchSchedulingService <- StubBatchSchedulingService.create(scheduledVideoDownload)
      heartBeats <- Ref.of[IO, List[(String, Instant)]](List.empty)

      // The message broker is unavailable for the whole download
      batchSchedulingService = new StubBatchSchedulingService(
        scheduledVideoDownload,
        stubBatchSchedulingService.statusUpdates,
        stubBatchSchedulingService.completedIds,
        stubBatchSchedulingService.progressUpdates
      ) {
        override def publishDownloadProgress(id: String, downloadedBytes: Long): IO[Unit] =
          IO.raiseError(new RuntimeException("Unable to send the message to Kafka"))
      }

      workExecutor =
        createWorkExecutor(
          Stream.emits[IO, Long](List(1_000, 2_500, 5_000)),
          batchSchedulingService,
          new RecordingWorkerDao(heartBeats)
        )

      result <- workExecutor.execute(scheduledVideoDownload, worker, Stream.empty, retries = 0).withTimeout(10.seconds)
      completedIds <- batchSchedulingService.completedIds.get
    } yield {
      result mustBe video
      completedIds mustBe List(videoMetadata.id)
    }
  }

  it should "complete a yt-dlp download even if saving its progress to the database fails" in runIO {
    val ytDlpVideoMetadata =
      videoMetadata.copy(
        url = Uri.unsafeFromString("https://www.youtube.com/watch?v=abc"),
        videoSite = VideoSite.YTDownloaderSite("youtube")
      )
    val ytDlpScheduledVideoDownload = scheduledVideoDownload.copy(videoMetadata = ytDlpVideoMetadata)

    val progress =
      Stream
        .awakeEvery[IO](1.second)
        .take(30)
        .zipWithIndex
        .map {
          case (_, index) =>
            YTDownloaderProgress(
              (index + 1) * 100.0 / 30,
              YTDataSize(5, YTDataUnit.KiB),
              YTDataSize(1, YTDataUnit.KiB),
              1.second
            )
        }

    // The database connection pool times out every time the progress is saved
    class FailingVideoMetadataDao extends NoOpVideoMetadataDao {
      @volatile var updateAttempts: Int = 0

      override def update(
        videoMetadataId: String,
        title: Option[String],
        size: Option[Long],
        maybeDuration: Option[FiniteDuration]
      ): IO[Int] =
        IO.delay(updateAttempts += 1) *> IO.raiseError(new RuntimeException("Connection is not available"))
    }

    val failingVideoMetadataDao = new FailingVideoMetadataDao

    val test =
      for {
        batchSchedulingService <- StubBatchSchedulingService.create(ytDlpScheduledVideoDownload)
        heartBeats <- Ref.of[IO, List[(String, Instant)]](List.empty)

        workExecutor =
          createWorkExecutor(
            Stream.empty,
            batchSchedulingService,
            new RecordingWorkerDao(heartBeats),
            videoMetadataDao = failingVideoMetadataDao,
            youTubeVideoDownloader = new StubYouTubeVideoDownloader(progress)
          )

        result <- workExecutor.execute(ytDlpScheduledVideoDownload, worker, Stream.never[IO], retries = 0).attempt
        completedIds <- batchSchedulingService.completedIds.get
      } yield (result, completedIds)

    TestControl.executeEmbed(test).map {
      case (result, completedIds) =>
        result mustBe Right(video)
        completedIds mustBe List(ytDlpVideoMetadata.id)
        failingVideoMetadataDao.updateAttempts must be >= 2
    }
  }

  it should "still fail when the download itself fails" in runIO {
    val failure = new RuntimeException("Connection reset by peer")

    for {
      batchSchedulingService <- StubBatchSchedulingService.create(scheduledVideoDownload)
      heartBeats <- Ref.of[IO, List[(String, Instant)]](List.empty)

      workExecutor =
        createWorkExecutor(
          Stream.emit[IO, Long](1_000) ++ Stream.raiseError[IO](failure),
          batchSchedulingService,
          new RecordingWorkerDao(heartBeats)
        )

      result <- workExecutor.execute(scheduledVideoDownload, worker, Stream.never[IO], retries = 0).attempt
      completedIds <- batchSchedulingService.completedIds.get
    } yield {
      result mustBe Left(failure)
      completedIds mustBe empty
    }
  }
}
