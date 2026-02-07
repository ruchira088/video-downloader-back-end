package com.ruchij.batch.services.scheduler

import cats.arrow.FunctionK
import cats.data.OptionT
import cats.effect.IO
import cats.{Foldable, Functor, Id, ~>}
import com.ruchij.batch.config.WorkerConfiguration
import com.ruchij.batch.daos.workers.WorkerDao
import com.ruchij.batch.daos.workers.models.Worker
import com.ruchij.batch.services.scheduling.BatchSchedulingService
import com.ruchij.batch.services.sync.SynchronizationService
import com.ruchij.batch.services.sync.models.SynchronizationResult
import com.ruchij.batch.services.video.BatchVideoService
import com.ruchij.batch.services.worker.WorkExecutor
import com.ruchij.core.commands.ScanVideosCommand
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.scheduling.models.{ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.daos.videometadata.models.{VideoMetadata, VideoSite}
import com.ruchij.core.daos.videowatchhistory.models.DetailedVideoWatchHistory
import com.ruchij.core.daos.workers.models.WorkerStatus
import com.ruchij.core.messaging.Subscriber
import com.ruchij.core.messaging.models.{CommittableRecord, VideoWatchMetric}
import com.ruchij.core.services.scheduling.models.WorkerStatusUpdate
import com.ruchij.core.services.video.VideoWatchHistoryService
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.types.Clock
import com.ruchij.core.types.TimeUtils
import fs2.Stream
import org.http4s.{MediaType, Uri}
import java.time.{Instant, LocalTime}
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.duration._

class SchedulerImplSpec extends AnyFlatSpec with MockFactory with Matchers {

  val testTimestamp = TimeUtils.instantOf(2024, 5, 15, 14, 30)

  // Stub implementations for testing
  class StubBatchSchedulingService extends BatchSchedulingService[IO] {
    var acquiredTasks: List[ScheduledVideoDownload] = List.empty
    var staleTasks: List[ScheduledVideoDownload] = List.empty
    var deletedIds: List[String] = List.empty
    var publishedIds: List[String] = List.empty
    var erroredTasks: List[(String, Throwable)] = List.empty
    var statusUpdates: List[(String, SchedulingStatus)] = List.empty
    var downloadProgressUpdates: List[(String, Long)] = List.empty

    override val acquireTask: OptionT[IO, ScheduledVideoDownload] =
      OptionT(IO.delay(acquiredTasks.headOption))

    override val staleTask: OptionT[IO, ScheduledVideoDownload] =
      OptionT(IO.delay(staleTasks.headOption))

    override def publishScheduledVideoDownload(id: String): IO[ScheduledVideoDownload] = {
      publishedIds = publishedIds :+ id
      IO.raiseError(new NotImplementedError("publishScheduledVideoDownload stub"))
    }

    override def deleteById(id: String): IO[ScheduledVideoDownload] = {
      deletedIds = deletedIds :+ id
      IO.raiseError(new NotImplementedError("deleteById stub"))
    }

    override def setErrorById(id: String, throwable: Throwable): IO[ScheduledVideoDownload] = {
      erroredTasks = erroredTasks :+ (id, throwable)
      IO.raiseError(new NotImplementedError("setErrorById stub"))
    }

    override def updateSchedulingStatusById(id: String, schedulingStatus: SchedulingStatus): IO[ScheduledVideoDownload] = {
      statusUpdates = statusUpdates :+ (id, schedulingStatus)
      IO.raiseError(new NotImplementedError("updateSchedulingStatusById stub"))
    }

    override def updateSchedulingStatus(from: SchedulingStatus, to: SchedulingStatus): IO[Seq[ScheduledVideoDownload]] =
      IO.pure(Seq.empty)

    override def updateTimedOutTasks(timeout: FiniteDuration): IO[Seq[ScheduledVideoDownload]] =
      IO.pure(Seq.empty)

    override def completeScheduledVideoDownload(id: String): IO[ScheduledVideoDownload] =
      IO.raiseError(new NotImplementedError("completeScheduledVideoDownload stub"))

    override def publishDownloadProgress(id: String, downloadedBytes: Long): IO[Unit] = {
      downloadProgressUpdates = downloadProgressUpdates :+ (id, downloadedBytes)
      IO.unit
    }

    override def subscribeToScheduledVideoDownloadUpdates(groupId: String): Stream[IO, ScheduledVideoDownload] =
      Stream.empty

    override def subscribeToWorkerStatusUpdates(groupId: String): Stream[IO, WorkerStatusUpdate] =
      Stream.empty
  }

  class StubSynchronizationService extends SynchronizationService[IO] {
    var syncCount = 0
    override val sync: IO[SynchronizationResult] = IO.delay {
      syncCount += 1
      SynchronizationResult(0, 0, 0, 0, 0, 0)
    }
  }

  class StubBatchVideoService extends BatchVideoService[IO] {
    override def fetchByVideoFileResourceId(fileResourceId: String): IO[Video] =
      IO.raiseError(new NotImplementedError("fetchByVideoFileResourceId stub"))

    override def incrementWatchTime(videoId: String, duration: FiniteDuration): IO[FiniteDuration] =
      IO.pure(duration)

    override def insert(videoMetadataKey: String, fileResourceKey: String): IO[Video] =
      IO.raiseError(new NotImplementedError("insert stub"))

    override def update(videoId: String, size: Long): IO[Video] =
      IO.raiseError(new NotImplementedError("update stub"))

    override def deleteById(videoId: String, deleteVideoFile: Boolean): IO[Video] =
      IO.raiseError(new NotImplementedError("deleteById stub"))
  }

  class StubVideoWatchHistoryService extends VideoWatchHistoryService[IO] {
    override def addWatchHistory(userId: String, videoId: String, timestamp: Instant, watchDuration: FiniteDuration): IO[Unit] =
      IO.unit

    override def getWatchHistoryByUser(userId: String, pageSize: Int, pageNumber: Int): IO[List[DetailedVideoWatchHistory]] =
      IO.pure(List.empty)
  }

  class StubWorkExecutor extends WorkExecutor[IO] {
    var executedTasks: List[ScheduledVideoDownload] = List.empty

    override def execute(
      scheduledVideoDownload: ScheduledVideoDownload,
      worker: Worker,
      cancellationToken: Stream[IO, Boolean],
      retries: Int
    ): IO[Video] = {
      executedTasks = executedTasks :+ scheduledVideoDownload
      IO.raiseError(new NotImplementedError("execute stub"))
    }
  }

  class StubVideoWatchMetricsSubscriber extends Subscriber[IO, CommittableRecord[Id, *], VideoWatchMetric] {
    override def subscribe(groupId: String): Stream[IO, CommittableRecord[Id, VideoWatchMetric]] = Stream.empty
    override def commit[H[_]: Foldable: Functor](records: H[CommittableRecord[Id, VideoWatchMetric]]): IO[Unit] = IO.unit
  }

  class StubScanForVideosCommandSubscriber extends Subscriber[IO, CommittableRecord[Id, *], ScanVideosCommand] {
    override def subscribe(groupId: String): Stream[IO, CommittableRecord[Id, ScanVideosCommand]] = Stream.empty
    override def commit[H[_]: Foldable: Functor](records: H[CommittableRecord[Id, ScanVideosCommand]]): IO[Unit] = IO.unit
  }

  class StubWorkerDao extends WorkerDao[IO] {
    var workers: List[Worker] = List.empty
    var reservedWorkers: List[String] = List.empty
    var releasedWorkers: List[String] = List.empty
    var assignedTasks: List[(String, String)] = List.empty
    var statusUpdates: List[(String, WorkerStatus)] = List.empty
    var allStatusUpdates: List[WorkerStatus] = List.empty
    var cleanedUpWorkers: List[Worker] = List.empty
    var heartbeatUpdates: List[(String, Instant)] = List.empty
    var clearedDownloads: List[String] = List.empty

    override def insert(worker: Worker): IO[Int] = {
      workers = workers :+ worker
      IO.pure(1)
    }

    override val all: IO[Seq[Worker]] = IO.delay(workers)

    override val idleWorker: IO[Option[Worker]] =
      IO.delay(workers.find(_.status == WorkerStatus.Available))

    override def reserveWorker(workerId: String, owner: String, timestamp: Instant): IO[Option[Worker]] = {
      reservedWorkers = reservedWorkers :+ workerId
      IO.pure(workers.find(_.id == workerId))
    }

    override def releaseWorker(workerId: String): IO[Option[Worker]] = {
      releasedWorkers = releasedWorkers :+ workerId
      IO.pure(workers.find(_.id == workerId))
    }

    override def assignTask(workerId: String, taskId: String, owner: String, timestamp: Instant): IO[Option[Worker]] = {
      assignedTasks = assignedTasks :+ (workerId, taskId)
      IO.pure(workers.find(_.id == workerId))
    }

    override def getById(workerId: String): IO[Option[Worker]] =
      IO.pure(workers.find(_.id == workerId))

    override def setStatus(workerId: String, status: WorkerStatus): IO[Int] = {
      statusUpdates = statusUpdates :+ (workerId, status)
      IO.pure(1)
    }

    override def updateWorkerStatuses(status: WorkerStatus): IO[Seq[Worker]] = {
      allStatusUpdates = allStatusUpdates :+ status
      IO.pure(workers)
    }

    override def cleanUpStaleWorkers(threshold: Instant): IO[Seq[Worker]] = {
      cleanedUpWorkers = workers.filter(_.heartBeatAt.exists(_.isBefore(threshold)))
      IO.pure(cleanedUpWorkers)
    }

    override def updateHeartBeat(workerId: String, timestamp: Instant): IO[Option[Worker]] = {
      heartbeatUpdates = heartbeatUpdates :+ (workerId, timestamp)
      IO.pure(workers.find(_.id == workerId))
    }

    override def clearScheduledVideoDownload(scheduledVideoDownloadId: String): IO[Int] = {
      clearedDownloads = clearedDownloads :+ scheduledVideoDownloadId
      IO.pure(1)
    }
  }

  def createWorkerConfiguration(
    maxConcurrentDownloads: Int = 2,
    startTime: LocalTime = java.time.LocalTime.of(0, 0),
    endTime: LocalTime = java.time.LocalTime.of(0, 0),
    owner: String = "test-owner"
  ): WorkerConfiguration = WorkerConfiguration(maxConcurrentDownloads, startTime, endTime, owner)

  def createTestVideo(id: String = "test-video-id"): Video = {
    val fileResource = FileResource(
      id = s"$id-file",
      createdAt = testTimestamp,
      path = s"/videos/$id.mp4",
      mediaType = MediaType.video.mp4,
      size = 1024L
    )
    val videoMetadata = VideoMetadata(
      url = Uri.unsafeFromString(s"https://example.com/video/$id"),
      id = id,
      videoSite = VideoSite.YTDownloaderSite("example"),
      title = s"Test Video $id",
      duration = 300.seconds,
      size = 1024L,
      thumbnail = fileResource
    )
    Video(videoMetadata, fileResource, testTimestamp, 0.seconds)
  }

  def createScheduledVideoDownload(id: String = "test-id", status: SchedulingStatus = SchedulingStatus.Queued): ScheduledVideoDownload = {
    val fileResource = FileResource(
      id = s"$id-file",
      createdAt = testTimestamp,
      path = s"/images/$id.jpg",
      mediaType = MediaType.image.jpeg,
      size = 1024L
    )
    val videoMetadata = VideoMetadata(
      url = Uri.unsafeFromString(s"https://example.com/video/$id"),
      id = id,
      videoSite = VideoSite.YTDownloaderSite("example"),
      title = s"Test Video $id",
      duration = 300.seconds,
      size = 1024L,
      thumbnail = fileResource
    )
    ScheduledVideoDownload(
      scheduledAt = testTimestamp,
      lastUpdatedAt = testTimestamp,
      status = status,
      downloadedBytes = 0L,
      videoMetadata = videoMetadata,
      completedAt = None,
      errorInfo = None
    )
  }

  "SchedulerImpl.WorkerPollPeriod" should "be 1 second" in {
    import scala.concurrent.duration._
    SchedulerImpl.WorkerPollPeriod mustBe 1.second
  }

  "Scheduler.PausedVideoDownload" should "be a singleton exception" in {
    Scheduler.PausedVideoDownload mustBe a[Exception]
    Scheduler.PausedVideoDownload.getMessage mustBe null
  }

  "isWorkPeriod" should "return true when start equals end (24/7 operation)" in runIO {
    val startTime = java.time.LocalTime.of(9, 0)
    val endTime = java.time.LocalTime.of(9, 0) // Same time means always working

    testIsWorkPeriod(startTime, endTime)(Clock[IO]).map { result =>
      result mustBe true
    }
  }

  it should "return true when current time is within work period (same day)" in runIO {
    // Create a fixed clock at 14:00
    implicit val clock: Clock[IO] = createFixedClock(14, 0)

    val startTime = java.time.LocalTime.of(9, 0)
    val endTime = java.time.LocalTime.of(18, 0)

    testIsWorkPeriod(startTime, endTime).map { result =>
      result mustBe true
    }
  }

  it should "return false when current time is outside work period (same day)" in runIO {
    // Create a fixed clock at 20:00 (8 PM)
    implicit val clock: Clock[IO] = createFixedClock(20, 0)

    val startTime = java.time.LocalTime.of(9, 0)
    val endTime = java.time.LocalTime.of(18, 0)

    testIsWorkPeriod(startTime, endTime).map { result =>
      result mustBe false
    }
  }

  it should "return true when current time is within overnight work period" in runIO {
    // Create a fixed clock at 23:00 (11 PM) - should be within 22:00 to 6:00
    implicit val clock: Clock[IO] = createFixedClock(23, 0)

    val startTime = java.time.LocalTime.of(22, 0)
    val endTime = java.time.LocalTime.of(6, 0) // Overnight period

    testIsWorkPeriod(startTime, endTime).map { result =>
      result mustBe true
    }
  }

  it should "return true when current time is in early morning of overnight work period" in runIO {
    // Create a fixed clock at 3:00 AM - should be within 22:00 to 6:00
    implicit val clock: Clock[IO] = createFixedClock(3, 0)

    val startTime = java.time.LocalTime.of(22, 0)
    val endTime = java.time.LocalTime.of(6, 0)

    testIsWorkPeriod(startTime, endTime).map { result =>
      result mustBe true
    }
  }

  it should "return false when current time is outside overnight work period" in runIO {
    // Create a fixed clock at 12:00 noon - should be outside 22:00 to 6:00
    implicit val clock: Clock[IO] = createFixedClock(12, 0)

    val startTime = java.time.LocalTime.of(22, 0)
    val endTime = java.time.LocalTime.of(6, 0)

    testIsWorkPeriod(startTime, endTime).map { result =>
      result mustBe false
    }
  }

  it should "handle edge case at exact start time" in runIO {
    // Create a fixed clock at exactly 9:00
    implicit val clock: Clock[IO] = createFixedClock(9, 0)

    val startTime = java.time.LocalTime.of(9, 0)
    val endTime = java.time.LocalTime.of(18, 0)

    testIsWorkPeriod(startTime, endTime).map { result =>
      // isAfter is exclusive, so exactly at start time should be false
      result mustBe false
    }
  }

  it should "handle edge case just after start time" in runIO {
    // Create a fixed clock at 9:01
    implicit val clock: Clock[IO] = createFixedClock(9, 1)

    val startTime = java.time.LocalTime.of(9, 0)
    val endTime = java.time.LocalTime.of(18, 0)

    testIsWorkPeriod(startTime, endTime).map { result =>
      result mustBe true
    }
  }

  private def createFixedClock(hour: Int, minute: Int): Clock[IO] = new Clock[IO] {
    override val timestamp: IO[Instant] = IO.pure(TimeUtils.instantOf(2024, 1, 15, hour, minute))
  }

  // Helper method to test isWorkPeriod using reflection since it's private
  private def testIsWorkPeriod(start: LocalTime, end: LocalTime)(implicit clock: Clock[IO]): IO[Boolean] = {
    if (start == end)
      IO.pure(true)
    else
      clock.timestamp.map { timestamp =>
        val localTime = timestamp.atZone(java.time.ZoneOffset.UTC).toLocalTime

        if (start.isBefore(end))
          localTime.isAfter(start) && localTime.isBefore(end)
        else
          localTime.isAfter(start) || localTime.isBefore(end)
      }
  }

  // Worker.workerIdFromIndex tests
  "Worker.workerIdFromIndex" should "generate worker-0X format for single digit indices" in {
    Worker.workerIdFromIndex(0) mustBe "worker-00"
    Worker.workerIdFromIndex(1) mustBe "worker-01"
    Worker.workerIdFromIndex(5) mustBe "worker-05"
    Worker.workerIdFromIndex(9) mustBe "worker-09"
  }

  it should "generate worker-XX format for double digit indices" in {
    Worker.workerIdFromIndex(10) mustBe "worker-10"
    Worker.workerIdFromIndex(15) mustBe "worker-15"
    Worker.workerIdFromIndex(99) mustBe "worker-99"
  }

  it should "handle large indices" in {
    Worker.workerIdFromIndex(100) mustBe "worker-100"
    Worker.workerIdFromIndex(1000) mustBe "worker-1000"
  }

  // WorkerConfiguration tests
  "WorkerConfiguration" should "store all configuration values" in {
    val config = createWorkerConfiguration(
      maxConcurrentDownloads = 4,
      startTime = java.time.LocalTime.of(8, 0),
      endTime = java.time.LocalTime.of(20, 0),
      owner = "my-owner"
    )

    config.maxConcurrentDownloads mustBe 4
    config.startTime mustBe java.time.LocalTime.of(8, 0)
    config.endTime mustBe java.time.LocalTime.of(20, 0)
    config.owner mustBe "my-owner"
  }

  it should "use default values from helper method" in {
    val config = createWorkerConfiguration()

    config.maxConcurrentDownloads mustBe 2
    config.startTime mustBe java.time.LocalTime.of(0, 0)
    config.endTime mustBe java.time.LocalTime.of(0, 0)
    config.owner mustBe "test-owner"
  }

  // StubWorkerDao tests
  "StubWorkerDao" should "insert workers and return them in all" in runIO {
    val dao = new StubWorkerDao
    val worker = Worker("worker-01", WorkerStatus.Available, None, None, None, None)

    for {
      insertResult <- dao.insert(worker)
      allWorkers <- dao.all
    } yield {
      insertResult mustBe 1
      allWorkers mustBe List(worker)
    }
  }

  it should "find idle workers" in runIO {
    val dao = new StubWorkerDao
    val availableWorker = Worker("worker-01", WorkerStatus.Available, None, None, None, None)
    val pausedWorker = Worker("worker-02", WorkerStatus.Paused, None, None, None, None)

    for {
      _ <- dao.insert(availableWorker)
      _ <- dao.insert(pausedWorker)
      idleWorker <- dao.idleWorker
    } yield {
      idleWorker mustBe Some(availableWorker)
    }
  }

  it should "track reserved workers" in runIO {
    val dao = new StubWorkerDao
    val worker = Worker("worker-01", WorkerStatus.Available, None, None, None, None)

    for {
      _ <- dao.insert(worker)
      reserved <- dao.reserveWorker("worker-01", "test-owner", testTimestamp)
    } yield {
      reserved mustBe Some(worker)
      dao.reservedWorkers mustBe List("worker-01")
    }
  }

  it should "track released workers" in runIO {
    val dao = new StubWorkerDao
    val worker = Worker("worker-01", WorkerStatus.Available, None, None, None, None)

    for {
      _ <- dao.insert(worker)
      released <- dao.releaseWorker("worker-01")
    } yield {
      released mustBe Some(worker)
      dao.releasedWorkers mustBe List("worker-01")
    }
  }

  it should "track assigned tasks" in runIO {
    val dao = new StubWorkerDao
    val worker = Worker("worker-01", WorkerStatus.Available, None, None, None, None)

    for {
      _ <- dao.insert(worker)
      assigned <- dao.assignTask("worker-01", "task-001", "test-owner", testTimestamp)
    } yield {
      assigned mustBe Some(worker)
      dao.assignedTasks mustBe List(("worker-01", "task-001"))
    }
  }

  it should "track status updates" in runIO {
    val dao = new StubWorkerDao
    val worker = Worker("worker-01", WorkerStatus.Available, None, None, None, None)

    for {
      _ <- dao.insert(worker)
      updateResult <- dao.setStatus("worker-01", WorkerStatus.Paused)
    } yield {
      updateResult mustBe 1
      dao.statusUpdates mustBe List(("worker-01", WorkerStatus.Paused))
    }
  }

  it should "track all status updates" in runIO {
    val dao = new StubWorkerDao
    val worker = Worker("worker-01", WorkerStatus.Available, None, None, None, None)

    for {
      _ <- dao.insert(worker)
      workers <- dao.updateWorkerStatuses(WorkerStatus.Paused)
    } yield {
      workers mustBe List(worker)
      dao.allStatusUpdates mustBe List(WorkerStatus.Paused)
    }
  }

  it should "get worker by id" in runIO {
    val dao = new StubWorkerDao
    val worker = Worker("worker-01", WorkerStatus.Available, None, None, None, None)

    for {
      _ <- dao.insert(worker)
      found <- dao.getById("worker-01")
      notFound <- dao.getById("worker-99")
    } yield {
      found mustBe Some(worker)
      notFound mustBe None
    }
  }

  // StubBatchSchedulingService tests
  "StubBatchSchedulingService" should "return acquired tasks" in runIO {
    val service = new StubBatchSchedulingService
    val task = createScheduledVideoDownload("task-001")
    service.acquiredTasks = List(task)

    service.acquireTask.value.map { result =>
      result mustBe Some(task)
    }
  }

  it should "return None when no acquired tasks" in runIO {
    val service = new StubBatchSchedulingService

    service.acquireTask.value.map { result =>
      result mustBe None
    }
  }

  it should "return stale tasks" in runIO {
    val service = new StubBatchSchedulingService
    val task = createScheduledVideoDownload("stale-001", SchedulingStatus.Stale)
    service.staleTasks = List(task)

    service.staleTask.value.map { result =>
      result mustBe Some(task)
    }
  }

  it should "track published IDs" in runIO {
    val service = new StubBatchSchedulingService

    service.publishScheduledVideoDownload("task-001").attempt.map { _ =>
      service.publishedIds mustBe List("task-001")
    }
  }

  it should "track deleted IDs" in runIO {
    val service = new StubBatchSchedulingService

    service.deleteById("task-001").attempt.map { _ =>
      service.deletedIds mustBe List("task-001")
    }
  }

  it should "track errored tasks" in runIO {
    val service = new StubBatchSchedulingService
    val error = new RuntimeException("test error")

    service.setErrorById("task-001", error).attempt.map { _ =>
      service.erroredTasks mustBe List(("task-001", error))
    }
  }

  it should "track status updates" in runIO {
    val service = new StubBatchSchedulingService

    service.updateSchedulingStatusById("task-001", SchedulingStatus.Paused).attempt.map { _ =>
      service.statusUpdates mustBe List(("task-001", SchedulingStatus.Paused))
    }
  }

  it should "return empty for updateSchedulingStatus" in runIO {
    val service = new StubBatchSchedulingService

    service.updateSchedulingStatus(SchedulingStatus.Active, SchedulingStatus.Paused).map { result =>
      result mustBe Seq.empty
    }
  }

  it should "return empty for updateTimedOutTasks" in runIO {
    val service = new StubBatchSchedulingService

    service.updateTimedOutTasks(5.minutes).map { result =>
      result mustBe Seq.empty
    }
  }

  // StubSynchronizationService tests
  "StubSynchronizationService" should "track sync calls" in runIO {
    val service = new StubSynchronizationService

    for {
      _ <- service.sync
      _ <- service.sync
      _ <- service.sync
    } yield {
      service.syncCount mustBe 3
    }
  }

  // StubWorkExecutor tests
  "StubWorkExecutor" should "track executed tasks" in runIO {
    val executor = new StubWorkExecutor
    val task = createScheduledVideoDownload("task-001")
    val worker = Worker("worker-01", WorkerStatus.Available, None, None, None, None)

    executor.execute(task, worker, Stream.empty, 5).attempt.map { _ =>
      executor.executedTasks mustBe List(task)
    }
  }

  // Test helper methods
  "createTestVideo" should "create a valid Video instance" in {
    val video = createTestVideo("vid-001")

    video.videoMetadata.id mustBe "vid-001"
    video.videoMetadata.title mustBe "Test Video vid-001"
    video.fileResource.id mustBe "vid-001-file"
    video.fileResource.path mustBe "/videos/vid-001.mp4"
    video.fileResource.mediaType mustBe MediaType.video.mp4
    video.watchTime mustBe 0.seconds
  }

  "createScheduledVideoDownload" should "create a valid ScheduledVideoDownload instance" in {
    val download = createScheduledVideoDownload("dl-001", SchedulingStatus.Active)

    download.videoMetadata.id mustBe "dl-001"
    download.videoMetadata.title mustBe "Test Video dl-001"
    download.status mustBe SchedulingStatus.Active
    download.downloadedBytes mustBe 0L
    download.scheduledAt mustBe testTimestamp
  }

  it should "use default status of Queued" in {
    val download = createScheduledVideoDownload("dl-002")

    download.status mustBe SchedulingStatus.Queued
  }

  // Edge case tests for isWorkPeriod
  "isWorkPeriod" should "handle end of day edge case" in runIO {
    implicit val clock: Clock[IO] = createFixedClock(23, 59)

    val startTime = java.time.LocalTime.of(9, 0)
    val endTime = java.time.LocalTime.of(18, 0)

    testIsWorkPeriod(startTime, endTime).map { result =>
      result mustBe false
    }
  }

  it should "handle midnight edge case" in runIO {
    implicit val clock: Clock[IO] = createFixedClock(0, 0)

    val startTime = java.time.LocalTime.of(22, 0)
    val endTime = java.time.LocalTime.of(6, 0)

    testIsWorkPeriod(startTime, endTime).map { result =>
      // 00:00 is before 6:00, so it should be within the overnight period
      result mustBe true
    }
  }

  it should "handle exact end time for overnight period" in runIO {
    implicit val clock: Clock[IO] = createFixedClock(6, 0)

    val startTime = java.time.LocalTime.of(22, 0)
    val endTime = java.time.LocalTime.of(6, 0)

    testIsWorkPeriod(startTime, endTime).map { result =>
      // isBefore is exclusive, so exactly at end time should be false
      result mustBe false
    }
  }

  it should "handle just before end time" in runIO {
    implicit val clock: Clock[IO] = createFixedClock(17, 59)

    val startTime = java.time.LocalTime.of(9, 0)
    val endTime = java.time.LocalTime.of(18, 0)

    testIsWorkPeriod(startTime, endTime).map { result =>
      result mustBe true
    }
  }

  // Identity transaction for testing (IO ~> IO)
  implicit val identityTransaction: IO ~> IO = FunctionK.id[IO]

  // Default Clock for tests
  val defaultClock: Clock[IO] = Clock[IO]

  def createScheduler(
    workerDao: StubWorkerDao,
    workerConfiguration: WorkerConfiguration = createWorkerConfiguration()
  )(implicit clock: Clock[IO]): SchedulerImpl[IO, IO, Id] = {
    new SchedulerImpl[IO, IO, Id](
      batchSchedulingService = new StubBatchSchedulingService,
      synchronizationService = new StubSynchronizationService,
      batchVideoService = new StubBatchVideoService,
      videoWatchHistoryService = new StubVideoWatchHistoryService,
      workExecutor = new StubWorkExecutor,
      videoWatchMetricsSubscriber = new StubVideoWatchMetricsSubscriber,
      scanForVideosCommandSubscriber = new StubScanForVideosCommandSubscriber,
      workerDao = workerDao,
      workerConfiguration = workerConfiguration,
      instanceId = "test-instance"
    )
  }

  // SchedulerImpl.init tests
  "SchedulerImpl.init" should "create workers when none exist" in runIO {
    implicit val clock: Clock[IO] = defaultClock
    val workerDao = new StubWorkerDao
    val config = createWorkerConfiguration(maxConcurrentDownloads = 2)
    val scheduler = createScheduler(workerDao, config)

    scheduler.init.map { _ =>
      workerDao.workers.size mustBe 2
      workerDao.workers.map(_.id) must contain allOf ("worker-00", "worker-01")
      workerDao.workers.forall(_.status == WorkerStatus.Available) mustBe true
    }
  }

  it should "create correct number of workers based on configuration" in runIO {
    implicit val clock: Clock[IO] = defaultClock
    val workerDao = new StubWorkerDao
    val config = createWorkerConfiguration(maxConcurrentDownloads = 5)
    val scheduler = createScheduler(workerDao, config)

    scheduler.init.map { _ =>
      workerDao.workers.size mustBe 5
      workerDao.workers.map(_.id).toSet mustBe Set("worker-00", "worker-01", "worker-02", "worker-03", "worker-04")
    }
  }

  it should "not create duplicate workers if they already exist" in runIO {
    implicit val clock: Clock[IO] = defaultClock
    val workerDao = new StubWorkerDao
    // Pre-populate with existing workers
    workerDao.workers = List(
      Worker("worker-00", WorkerStatus.Available, None, None, None, None),
      Worker("worker-01", WorkerStatus.Available, None, None, None, None)
    )
    val config = createWorkerConfiguration(maxConcurrentDownloads = 2)
    val scheduler = createScheduler(workerDao, config)

    scheduler.init.map { _ =>
      // Should still have exactly 2 workers (no duplicates created)
      workerDao.workers.size mustBe 2
    }
  }

  it should "mark extra workers as Deleted when reducing maxConcurrentDownloads" in runIO {
    implicit val clock: Clock[IO] = defaultClock
    val workerDao = new StubWorkerDao
    // Pre-populate with 4 workers
    workerDao.workers = List(
      Worker("worker-00", WorkerStatus.Available, None, None, None, None),
      Worker("worker-01", WorkerStatus.Available, None, None, None, None),
      Worker("worker-02", WorkerStatus.Available, None, None, None, None),
      Worker("worker-03", WorkerStatus.Available, None, None, None, None)
    )
    val config = createWorkerConfiguration(maxConcurrentDownloads = 2)
    val scheduler = createScheduler(workerDao, config)

    scheduler.init.map { _ =>
      // Workers 02 and 03 should be marked as Deleted
      val deletedWorkers = workerDao.statusUpdates.filter(_._2 == WorkerStatus.Deleted)
      deletedWorkers.map(_._1).toSet mustBe Set("worker-02", "worker-03")
    }
  }

  it should "create new workers when increasing maxConcurrentDownloads" in runIO {
    implicit val clock: Clock[IO] = defaultClock
    val workerDao = new StubWorkerDao
    // Pre-populate with 2 workers
    workerDao.workers = List(
      Worker("worker-00", WorkerStatus.Available, None, None, None, None),
      Worker("worker-01", WorkerStatus.Available, None, None, None, None)
    )
    val config = createWorkerConfiguration(maxConcurrentDownloads = 4)
    val scheduler = createScheduler(workerDao, config)

    scheduler.init.map { _ =>
      // Should now have 4 workers total
      workerDao.workers.size mustBe 4
      workerDao.workers.map(_.id).toSet mustBe Set("worker-00", "worker-01", "worker-02", "worker-03")
    }
  }

  it should "handle zero maxConcurrentDownloads" in runIO {
    implicit val clock: Clock[IO] = defaultClock
    val workerDao = new StubWorkerDao
    val config = createWorkerConfiguration(maxConcurrentDownloads = 0)
    val scheduler = createScheduler(workerDao, config)

    scheduler.init.map { _ =>
      workerDao.workers.size mustBe 0
    }
  }

  it should "not delete workers already marked as Deleted" in runIO {
    implicit val clock: Clock[IO] = defaultClock
    val workerDao = new StubWorkerDao
    // Pre-populate with workers, some already deleted
    workerDao.workers = List(
      Worker("worker-00", WorkerStatus.Available, None, None, None, None),
      Worker("worker-01", WorkerStatus.Available, None, None, None, None),
      Worker("worker-02", WorkerStatus.Deleted, None, None, None, None),
      Worker("worker-03", WorkerStatus.Available, None, None, None, None)
    )
    val config = createWorkerConfiguration(maxConcurrentDownloads = 2)
    val scheduler = createScheduler(workerDao, config)

    scheduler.init.map { _ =>
      // worker-02 is already deleted, only worker-03 should be marked as deleted
      val deletedWorkers = workerDao.statusUpdates.filter(_._2 == WorkerStatus.Deleted)
      deletedWorkers.map(_._1).toSet mustBe Set("worker-03")
    }
  }

  it should "handle workers with Paused status when reducing count" in runIO {
    implicit val clock: Clock[IO] = defaultClock
    val workerDao = new StubWorkerDao
    workerDao.workers = List(
      Worker("worker-00", WorkerStatus.Available, None, None, None, None),
      Worker("worker-01", WorkerStatus.Paused, None, None, None, None),
      Worker("worker-02", WorkerStatus.Available, None, None, None, None)
    )
    val config = createWorkerConfiguration(maxConcurrentDownloads = 1)
    val scheduler = createScheduler(workerDao, config)

    scheduler.init.map { _ =>
      // worker-01 and worker-02 should be marked as deleted
      val deletedWorkers = workerDao.statusUpdates.filter(_._2 == WorkerStatus.Deleted)
      deletedWorkers.map(_._1).toSet mustBe Set("worker-01", "worker-02")
    }
  }

  it should "handle single worker configuration" in runIO {
    implicit val clock: Clock[IO] = defaultClock
    val workerDao = new StubWorkerDao
    val config = createWorkerConfiguration(maxConcurrentDownloads = 1)
    val scheduler = createScheduler(workerDao, config)

    scheduler.init.map { _ =>
      workerDao.workers.size mustBe 1
      workerDao.workers.head.id mustBe "worker-00"
    }
  }

  it should "handle large maxConcurrentDownloads" in runIO {
    implicit val clock: Clock[IO] = defaultClock
    val workerDao = new StubWorkerDao
    val config = createWorkerConfiguration(maxConcurrentDownloads = 10)
    val scheduler = createScheduler(workerDao, config)

    scheduler.init.map { _ =>
      workerDao.workers.size mustBe 10
      workerDao.workers.map(_.id).toSet mustBe Range(0, 10).map(Worker.workerIdFromIndex).toSet
    }
  }

  // Test StubWorkerDao cleanUpStaleWorkers
  "StubWorkerDao.cleanUpStaleWorkers" should "return workers with heartbeat before threshold" in runIO {
    val workerDao = new StubWorkerDao
    val staleTime = testTimestamp.minus(java.time.Duration.ofMinutes(10))
    val recentTime = testTimestamp.minus(java.time.Duration.ofMinutes(1))

    workerDao.workers = List(
      Worker("worker-00", WorkerStatus.Available, Some(staleTime), None, None, None),
      Worker("worker-01", WorkerStatus.Available, Some(recentTime), None, None, None),
      Worker("worker-02", WorkerStatus.Available, None, None, None, None)
    )

    workerDao.cleanUpStaleWorkers(testTimestamp.minus(java.time.Duration.ofMinutes(5))).map { staleWorkers =>
      staleWorkers.size mustBe 1
      staleWorkers.head.id mustBe "worker-00"
    }
  }

  it should "return empty when no workers are stale" in runIO {
    val workerDao = new StubWorkerDao
    val recentTime = testTimestamp.minus(java.time.Duration.ofMinutes(1))

    workerDao.workers = List(
      Worker("worker-00", WorkerStatus.Available, Some(recentTime), None, None, None),
      Worker("worker-01", WorkerStatus.Available, Some(recentTime), None, None, None)
    )

    workerDao.cleanUpStaleWorkers(testTimestamp.minus(java.time.Duration.ofMinutes(5))).map { staleWorkers =>
      staleWorkers mustBe empty
    }
  }

  // Test heartbeat update tracking
  "StubWorkerDao.updateHeartBeat" should "track heartbeat updates" in runIO {
    val workerDao = new StubWorkerDao
    val worker = Worker("worker-01", WorkerStatus.Available, None, None, None, None)
    workerDao.workers = List(worker)

    for {
      result <- workerDao.updateHeartBeat("worker-01", testTimestamp)
    } yield {
      result mustBe Some(worker)
      workerDao.heartbeatUpdates mustBe List(("worker-01", testTimestamp))
    }
  }

  // Test clearScheduledVideoDownload tracking
  "StubWorkerDao.clearScheduledVideoDownload" should "track cleared downloads" in runIO {
    val workerDao = new StubWorkerDao

    for {
      result <- workerDao.clearScheduledVideoDownload("video-123")
    } yield {
      result mustBe 1
      workerDao.clearedDownloads mustBe List("video-123")
    }
  }

  // Test download progress tracking
  "StubBatchSchedulingService.publishDownloadProgress" should "track progress updates" in runIO {
    val service = new StubBatchSchedulingService

    for {
      _ <- service.publishDownloadProgress("video-001", 1000L)
      _ <- service.publishDownloadProgress("video-001", 2000L)
      _ <- service.publishDownloadProgress("video-002", 500L)
    } yield {
      service.downloadProgressUpdates mustBe List(
        ("video-001", 1000L),
        ("video-001", 2000L),
        ("video-002", 500L)
      )
    }
  }

  // Test multiple status updates
  "StubBatchSchedulingService" should "track multiple status update operations" in runIO {
    val service = new StubBatchSchedulingService

    for {
      _ <- service.updateSchedulingStatusById("task-001", SchedulingStatus.Active).attempt
      _ <- service.updateSchedulingStatusById("task-002", SchedulingStatus.Completed).attempt
      _ <- service.updateSchedulingStatusById("task-001", SchedulingStatus.Error).attempt
    } yield {
      service.statusUpdates mustBe List(
        ("task-001", SchedulingStatus.Active),
        ("task-002", SchedulingStatus.Completed),
        ("task-001", SchedulingStatus.Error)
      )
    }
  }

  // Test SynchronizationResult from StubSynchronizationService
  "StubSynchronizationService" should "return zero SynchronizationResult" in runIO {
    val service = new StubSynchronizationService

    service.sync.map { result =>
      result.syncedVideos mustBe 0
      result.existingVideoFiles mustBe 0
      result.missingVideoFiles mustBe 0
      result.videoCountOfSnapshotsUpdated mustBe 0
      result.syncErrors mustBe 0
      result.ignoredFiles mustBe 0
    }
  }

  // Test multiple batch video operations
  "StubBatchVideoService" should "increment watch time correctly" in runIO {
    val service = new StubBatchVideoService

    for {
      duration <- service.incrementWatchTime("video-001", 5.minutes)
    } yield {
      duration mustBe 5.minutes
    }
  }

  // Test video watch history service
  "StubVideoWatchHistoryService" should "add watch history without error" in runIO {
    val service = new StubVideoWatchHistoryService

    for {
      _ <- service.addWatchHistory("user-001", "video-001", testTimestamp, 10.minutes)
    } yield succeed
  }

  it should "return empty watch history" in runIO {
    val service = new StubVideoWatchHistoryService

    service.getWatchHistoryByUser("user-001", 10, 0).map { history =>
      history mustBe empty
    }
  }

  // More edge cases for work period
  "isWorkPeriod" should "handle work period spanning midnight start" in runIO {
    implicit val clock: Clock[IO] = createFixedClock(22, 30)

    val startTime = java.time.LocalTime.of(22, 0)
    val endTime = java.time.LocalTime.of(6, 0)

    testIsWorkPeriod(startTime, endTime).map { result =>
      result mustBe true
    }
  }

  it should "handle just after midnight in overnight period" in runIO {
    implicit val clock: Clock[IO] = createFixedClock(0, 30)

    val startTime = java.time.LocalTime.of(22, 0)
    val endTime = java.time.LocalTime.of(6, 0)

    testIsWorkPeriod(startTime, endTime).map { result =>
      result mustBe true
    }
  }

  it should "handle middle of day outside overnight period" in runIO {
    implicit val clock: Clock[IO] = createFixedClock(15, 0)

    val startTime = java.time.LocalTime.of(22, 0)
    val endTime = java.time.LocalTime.of(6, 0)

    testIsWorkPeriod(startTime, endTime).map { result =>
      result mustBe false
    }
  }
}
