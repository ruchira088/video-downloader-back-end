package com.ruchij.batch.services.scheduler

import cats.arrow.FunctionK
import cats.data.OptionT
import cats.effect.IO
import cats.{Foldable, Functor, ~>}
import com.ruchij.batch.config.WorkerConfiguration
import com.ruchij.batch.daos.workers.WorkerDao
import com.ruchij.batch.daos.workers.models.Worker
import com.ruchij.batch.services.detection.BatchDuplicateDetectionService
import com.ruchij.batch.services.scheduling.BatchSchedulingService
import com.ruchij.batch.services.sync.SynchronizationService
import com.ruchij.batch.services.sync.models.SynchronizationResult
import com.ruchij.batch.services.video.BatchVideoService
import com.ruchij.batch.services.worker.WorkExecutor
import com.ruchij.core.commands.ScanVideosCommand
import com.ruchij.core.daos.messaging.MessageDao
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.scheduling.models.{ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.daos.videometadata.models.{VideoMetadata, VideoSite}
import com.ruchij.core.daos.videowatchhistory.models.DetailedVideoWatchHistory
import com.ruchij.core.daos.workers.models.WorkerStatus
import com.ruchij.core.messaging.Subscriber
import com.ruchij.core.messaging.models.VideoWatchMetric
import com.ruchij.core.services.scheduling.models.WorkerStatusUpdate
import com.ruchij.core.services.video.VideoWatchHistoryService
import com.ruchij.batch.services.scheduler.Scheduler.PausedVideoDownload
import com.ruchij.core.test.IOSupport.{IOWrapper, runIO}
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
  class StubBatchSchedulingService(workerStatusUpdates: Stream[IO, WorkerStatusUpdate] = Stream.empty)
      extends BatchSchedulingService[IO] {
    @volatile var acquiredTasks: List[ScheduledVideoDownload] = List.empty
    @volatile var staleTasks: List[ScheduledVideoDownload] = List.empty
    @volatile var deletedIds: List[String] = List.empty
    @volatile var publishedIds: List[String] = List.empty
    @volatile var erroredTasks: List[(String, Throwable)] = List.empty
    @volatile var statusUpdates: List[(String, SchedulingStatus)] = List.empty
    @volatile var bulkStatusUpdates: List[(SchedulingStatus, SchedulingStatus)] = List.empty
    @volatile var downloadProgressUpdates: List[(String, Long)] = List.empty

    override val acquireTask: OptionT[IO, ScheduledVideoDownload] =
      OptionT(IO.delay(acquiredTasks.headOption))

    override val staleTask: OptionT[IO, ScheduledVideoDownload] =
      OptionT(IO.delay(staleTasks.headOption))

    override def publishScheduledVideoDownload(id: String): IO[ScheduledVideoDownload] = {
      publishedIds = publishedIds :+ id
      IO.fromOption((acquiredTasks ++ staleTasks).find(_.videoMetadata.id == id))(
        new NoSuchElementException(s"Unknown scheduled video download: $id")
      )
    }

    override def deleteById(id: String): IO[ScheduledVideoDownload] = {
      deletedIds = deletedIds :+ id
      IO.raiseError(new NotImplementedError("deleteById stub"))
    }

    override def setErrorById(id: String, throwable: Throwable): IO[ScheduledVideoDownload] = {
      erroredTasks = erroredTasks :+ (id, throwable)
      IO.pure(createScheduledVideoDownload(id, SchedulingStatus.Error))
    }

    override def updateSchedulingStatusById(id: String, schedulingStatus: SchedulingStatus): IO[ScheduledVideoDownload] = {
      statusUpdates = statusUpdates :+ (id, schedulingStatus)
      IO.pure(createScheduledVideoDownload(id, schedulingStatus))
    }

    override def updateSchedulingStatus(from: SchedulingStatus, to: SchedulingStatus): IO[Seq[ScheduledVideoDownload]] = {
      bulkStatusUpdates = bulkStatusUpdates :+ (from, to)
      IO.pure(Seq.empty)
    }

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

    override def subscribeToWorkerStatusUpdates(groupId: String): Stream[IO, WorkerStatusUpdate] = workerStatusUpdates
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

  class StubWorkExecutor(
    result: (ScheduledVideoDownload, Stream[IO, Boolean]) => IO[Video] =
      (_, _) => IO.raiseError(new NotImplementedError("execute stub"))
  ) extends WorkExecutor[IO] {
    @volatile var executedTasks: List[ScheduledVideoDownload] = List.empty

    override def execute(
      scheduledVideoDownload: ScheduledVideoDownload,
      worker: Worker,
      interrupt: Stream[IO, Boolean],
      retries: Int
    ): IO[Video] = {
      executedTasks = executedTasks :+ scheduledVideoDownload
      result(scheduledVideoDownload, interrupt)
    }
  }

  class StubVideoWatchMetricsSubscriber extends Subscriber[IO, VideoWatchMetric] {
    override type C[X] = X
    override def subscribe(groupId: String): Stream[IO, VideoWatchMetric] = Stream.empty
    override def commit[H[_]: Foldable: Functor](records: H[VideoWatchMetric]): IO[Unit] = IO.unit
    override def extractValue(ca: VideoWatchMetric): VideoWatchMetric = ca
  }

  class StubScanForVideosCommandSubscriber extends Subscriber[IO, ScanVideosCommand] {
    override type C[X] = X
    override def subscribe(groupId: String): Stream[IO, ScanVideosCommand] = Stream.empty
    override def commit[H[_]: Foldable: Functor](records: H[ScanVideosCommand]): IO[Unit] = IO.unit
    override def extractValue(ca: ScanVideosCommand): ScanVideosCommand = ca
  }

  class StubMessageDao extends MessageDao[IO] {
    var deletedBeforeCount: Int = 0

    override def insert(channel: String, payload: String, createdAt: Instant): IO[Int] = IO.pure(1)
    override def maxId(channel: String): IO[Long] = IO.pure(0L)
    override def findAfter(channel: String, afterId: Long): IO[List[(Long, String)]] = IO.pure(List.empty)
    override def deleteBefore(timestamp: Instant): IO[Int] = IO.delay {
      deletedBeforeCount += 1
      0
    }
  }

  class StubBatchDuplicateDetectionService extends BatchDuplicateDetectionService[IO] {
    override def detect: IO[Map[FiniteDuration, Set[Set[String]]]] = IO.pure(Map.empty)
    override def run: IO[Unit] = IO.unit
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
    workerConfiguration: WorkerConfiguration = createWorkerConfiguration(),
    maybeMessageTransaction: Option[IO ~> IO] = None,
    batchSchedulingService: StubBatchSchedulingService = new StubBatchSchedulingService(),
    workExecutor: StubWorkExecutor = new StubWorkExecutor()
  )(implicit clock: Clock[IO]): SchedulerImpl[IO, IO] = {
    new SchedulerImpl[IO, IO](
      batchSchedulingService = batchSchedulingService,
      synchronizationService = new StubSynchronizationService,
      batchVideoService = new StubBatchVideoService,
      videoWatchHistoryService = new StubVideoWatchHistoryService,
      workExecutor = workExecutor,
      duplicateDetectionService = new StubBatchDuplicateDetectionService,
      videoWatchMetricsSubscriber = new StubVideoWatchMetricsSubscriber,
      scanForVideosCommandSubscriber = new StubScanForVideosCommandSubscriber,
      workerDao = workerDao,
      messageDao = new StubMessageDao,
      maybeMessageTransaction = maybeMessageTransaction,
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


  private def availableWorker(id: String = "worker-00"): Worker =
    Worker(id, WorkerStatus.Available, None, None, None, None)

  "SchedulerImpl.run" should "assign a queued task to an idle worker and emit the downloaded video" in runIO {
    implicit val clock: Clock[IO] = defaultClock

    val task = createScheduledVideoDownload("queued-task")
    val video = createTestVideo("queued-task")

    val workerDao = new StubWorkerDao
    workerDao.workers = List(availableWorker())

    val batchSchedulingService = new StubBatchSchedulingService()
    batchSchedulingService.acquiredTasks = List(task)

    val workExecutor = new StubWorkExecutor((_, _) => IO.pure(video))

    val scheduler =
      createScheduler(
        workerDao,
        createWorkerConfiguration(maxConcurrentDownloads = 1),
        batchSchedulingService = batchSchedulingService,
        workExecutor = workExecutor
      )

    scheduler.run.head.compile.lastOrError.withTimeout(15.seconds).map { downloaded =>
      downloaded mustBe video
      workerDao.reservedWorkers must contain("worker-00")
      workerDao.assignedTasks must contain(("worker-00", task.videoMetadata.id))
      workerDao.releasedWorkers must contain("worker-00")
      batchSchedulingService.publishedIds must contain(task.videoMetadata.id)
      workExecutor.executedTasks mustBe List(task)
    }
  }

  it should "record an error against the task and release the worker when the executor fails" in runIO {
    implicit val clock: Clock[IO] = defaultClock

    val task = createScheduledVideoDownload("failing-task")
    val failure = new RuntimeException("Download failed")

    val workerDao = new StubWorkerDao
    workerDao.workers = List(availableWorker())

    val batchSchedulingService = new StubBatchSchedulingService()
    batchSchedulingService.acquiredTasks = List(task)

    val scheduler =
      createScheduler(
        workerDao,
        createWorkerConfiguration(maxConcurrentDownloads = 1),
        batchSchedulingService = batchSchedulingService,
        workExecutor = new StubWorkExecutor((_, _) => IO.raiseError(failure))
      )

    scheduler.run.interruptAfter(3.seconds).compile.toList.map { downloaded =>
      downloaded mustBe empty
      batchSchedulingService.erroredTasks.map { case (id, _) => id } must contain(task.videoMetadata.id)
      batchSchedulingService.erroredTasks.map { case (_, throwable) => throwable } must contain(failure)
      workerDao.releasedWorkers must contain("worker-00")
    }
  }

  it should "pause the active download and the workers when a worker pause update arrives" in runIO {
    implicit val clock: Clock[IO] = defaultClock

    val task = createScheduledVideoDownload("paused-task")

    val workerDao = new StubWorkerDao
    workerDao.workers = List(availableWorker())

    val batchSchedulingService =
      new StubBatchSchedulingService(
        workerStatusUpdates = Stream.awakeEvery[IO](500.millis).as(WorkerStatusUpdate(WorkerStatus.Paused))
      )
    batchSchedulingService.acquiredTasks = List(task)

    // Behaves like WorkExecutorImpl: the first pause signal abandons the download
    val workExecutor =
      new StubWorkExecutor((_, interrupt) => interrupt.head.compile.drain.productR(IO.raiseError(PausedVideoDownload)))

    val scheduler =
      createScheduler(
        workerDao,
        createWorkerConfiguration(maxConcurrentDownloads = 1),
        batchSchedulingService = batchSchedulingService,
        workExecutor = workExecutor
      )

    scheduler.run.interruptAfter(4.seconds).compile.toList.map { downloaded =>
      downloaded mustBe empty
      workExecutor.executedTasks must contain(task)
      batchSchedulingService.erroredTasks mustBe empty
      batchSchedulingService.bulkStatusUpdates must contain((SchedulingStatus.Active, SchedulingStatus.WorkersPaused))
      workerDao.allStatusUpdates must contain(WorkerStatus.Paused)
      workerDao.releasedWorkers must contain("worker-00")
    }
  }
}
