package com.ruchij.batch.services.worker

import cats.arrow.FunctionK
import cats.effect.{IO, Ref}
import cats.~>
import com.ruchij.batch.daos.workers.WorkerDao
import com.ruchij.batch.daos.workers.models.Worker
import com.ruchij.batch.services.scheduler.Scheduler.PausedVideoDownload
import com.ruchij.batch.test.stubs.BatchStubs._
import com.ruchij.core.config.StorageConfiguration
import com.ruchij.core.daos.resource.FileResourceDao
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.scheduling.models.SchedulingStatus
import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.daos.videometadata.VideoMetadataDao
import com.ruchij.core.daos.videometadata.models.{CustomVideoSite, VideoMetadata}
import com.ruchij.core.daos.workers.models.WorkerStatus
import com.ruchij.core.services.download.models.DownloadResult
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

  private val worker = Worker("worker-00", WorkerStatus.Active, None, None, None, None)

  private implicit val identityTransaction: IO ~> IO = FunctionK.id[IO]

  private implicit val clock: Clock[IO] = com.ruchij.core.test.Providers.stubClock[IO](timestamp)

  private class RecordingWorkerDao(val heartBeats: Ref[IO, List[Instant]]) extends WorkerDao[IO] {
    private def notImplemented[A]: IO[A] = IO.raiseError(new NotImplementedError("Not used by WorkExecutorImpl"))

    override val idleWorker: IO[Option[Worker]] = notImplemented
    override val all: IO[Seq[Worker]] = notImplemented
    override def insert(worker: Worker): IO[Int] = notImplemented
    override def getById(workerId: String): IO[Option[Worker]] = notImplemented
    override def setStatus(workerId: String, workerStatus: WorkerStatus): IO[Int] = notImplemented
    override def reserveWorker(workerId: String, owner: String, timestamp: Instant): IO[Option[Worker]] = notImplemented
    override def assignTask(workerId: String, scheduledVideoId: String, owner: String, timestamp: Instant): IO[Option[Worker]] =
      notImplemented
    override def releaseWorker(workerId: String): IO[Option[Worker]] = notImplemented
    override def updateHeartBeat(workerId: String, timestamp: Instant): IO[Option[Worker]] =
      heartBeats.update(_ :+ timestamp).as(Some(worker))
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
    workerDao: WorkerDao[IO]
  ): WorkExecutorImpl[IO, IO] =
    new WorkExecutorImpl[IO, IO](
      new NoOpFileResourceDao,
      workerDao,
      new NoOpVideoMetadataDao,
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
      new StubYouTubeVideoDownloader(),
      new StubVideoEnrichmentService(timestamp),
      StorageConfiguration("/videos", "/images", List.empty)
    )

  "execute" should "fail with PausedVideoDownload and leave the download incomplete when the interrupt fires" in runIO {
    for {
      batchSchedulingService <- StubBatchSchedulingService.create(scheduledVideoDownload)
      heartBeats <- Ref.of[IO, List[Instant]](List.empty)

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
      heartBeats <- Ref.of[IO, List[Instant]](List.empty)

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
}
