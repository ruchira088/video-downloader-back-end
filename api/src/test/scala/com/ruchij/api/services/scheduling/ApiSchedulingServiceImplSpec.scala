package com.ruchij.api.services.scheduling

import cats.data.NonEmptyList
import cats.effect.IO
import cats.~>
import com.ruchij.api.services.config.models.ApiConfigKey
import com.ruchij.api.services.scheduling.models.ScheduledVideoResult
import com.ruchij.core.daos.permission.VideoPermissionDao
import com.ruchij.core.daos.permission.models.VideoPermission
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.scheduling.SchedulingDao
import com.ruchij.core.daos.scheduling.models.{RangeValue, ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.daos.title.VideoTitleDao
import com.ruchij.core.daos.title.models.VideoTitle
import com.ruchij.core.daos.videometadata.models.{VideoMetadata, VideoSite}
import com.ruchij.core.daos.workers.models.WorkerStatus
import com.ruchij.core.exceptions.{InvalidConditionException, ResourceNotFoundException, ValidationException}
import com.ruchij.core.kv.codecs.{KVCodec, KVDecoder}
import com.ruchij.core.messaging.Publisher
import com.ruchij.core.services.config.ConfigurationService
import com.ruchij.core.services.models.{Order, SortBy}
import com.ruchij.core.services.scheduling.models.WorkerStatusUpdate
import com.ruchij.core.services.video.VideoAnalysisService
import com.ruchij.core.services.video.VideoAnalysisService.{NewlyCreated, VideoMetadataResult}
import com.ruchij.core.services.video.models.VideoAnalysisResult
import com.ruchij.core.test.IOSupport.{IOWrapper, runIO}
import com.ruchij.core.test.Providers
import com.ruchij.core.types.{Clock, TimeUtils}
import org.http4s.implicits._
import org.http4s.{MediaType, Uri}
import java.time.Instant
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.collection.mutable
import scala.concurrent.duration._

class ApiSchedulingServiceImplSpec extends AnyFlatSpec with Matchers {

  private val timestamp = TimeUtils.instantOf(2024, 5, 15, 10, 30)

  implicit val transaction: IO ~> IO = new (IO ~> IO) {
    override def apply[A](fa: IO[A]): IO[A] = fa
  }

  private val sampleThumbnail = FileResource(
    "thumbnail-1",
    timestamp,
    "/thumbnails/thumb1.jpg",
    MediaType.image.jpeg,
    50000L
  )

  private val sampleVideoMetadata = VideoMetadata(
    uri"https://example.com/video1",
    "video-1",
    VideoSite.YTDownloaderSite("youtube"),
    "Sample Video",
    10.minutes,
    1024 * 1024 * 100L,
    sampleThumbnail
  )

  private val sampleScheduledVideoDownload = ScheduledVideoDownload(
    timestamp,
    timestamp,
    SchedulingStatus.Queued,
    0,
    sampleVideoMetadata,
    None,
    None
  )

  class StubVideoAnalysisService(
    metadataResult: Uri => IO[VideoMetadataResult] = _ => IO.raiseError(new RuntimeException("Not implemented"))
  ) extends VideoAnalysisService[IO] {
    override def metadata(uri: Uri): IO[VideoMetadataResult] = metadataResult(uri)
    override def analyze(uri: Uri): IO[VideoAnalysisResult] = IO.raiseError(new RuntimeException("Not implemented"))
    override def downloadUri(uri: Uri): IO[Uri] = IO.raiseError(new RuntimeException("Not implemented"))
    override def videoDurationFromPath(videoPath: String): IO[FiniteDuration] = IO.raiseError(new RuntimeException("Not implemented"))
  }

  class StubPublisher[A] extends Publisher[IO, A] {
    val publishedMessages: mutable.ListBuffer[A] = mutable.ListBuffer.empty[A]

    override val publish: fs2.Pipe[IO, A, Unit] =
      stream => stream.evalMap(a => IO.delay(publishedMessages += a).void)

    override def publishOne(message: A): IO[Unit] =
      IO.delay(publishedMessages += message).void
  }

  class StubConfigurationService(
    storage: mutable.Map[String, WorkerStatus] = mutable.Map.empty
  ) extends ConfigurationService[IO, ApiConfigKey] {
    override def get[A: KVDecoder[IO, *], K[_] <: ApiConfigKey[_]](key: K[A]): IO[Option[A]] =
      IO.pure(storage.get(key.key).map(_.asInstanceOf[A]))

    override def put[A: KVCodec[IO, *], K[_] <: ApiConfigKey[_]](key: K[A], value: A): IO[Option[A]] =
      IO.delay {
        val old = storage.get(key.key).map(_.asInstanceOf[A])
        storage.put(key.key, value.asInstanceOf[WorkerStatus])
        old
      }

    override def delete[A: KVDecoder[IO, *], K[_] <: ApiConfigKey[_]](key: K[A]): IO[Option[A]] =
      IO.delay { storage.remove(key.key).map(_.asInstanceOf[A]) }
  }

  class StubSchedulingDao(
    searchResult: Seq[ScheduledVideoDownload] = Seq.empty,
    getByIdResult: (String, Option[String]) => Option[ScheduledVideoDownload] = (_, _) => None,
    updateSchedulingStatusResult: Option[ScheduledVideoDownload] = None,
    updateDownloadProgressResult: Option[ScheduledVideoDownload] = None,
    retryErroredResult: Seq[ScheduledVideoDownload] = Seq.empty
  ) extends SchedulingDao[IO] {
    override def insert(scheduledVideoDownload: ScheduledVideoDownload): IO[Int] = IO.pure(1)
    override def getById(id: String, maybeUserId: Option[String]): IO[Option[ScheduledVideoDownload]] =
      IO.pure(getByIdResult(id, maybeUserId))
    override def markScheduledVideoDownloadAsComplete(id: String, timestamp: Instant): IO[Option[ScheduledVideoDownload]] = IO.pure(None)
    override def updateSchedulingStatusById(id: String, status: SchedulingStatus, timestamp: Instant): IO[Option[ScheduledVideoDownload]] =
      IO.pure(updateSchedulingStatusResult)
    override def setErrorById(id: String, throwable: Throwable, timestamp: Instant): IO[Option[ScheduledVideoDownload]] = IO.pure(None)
    override def updateSchedulingStatus(from: SchedulingStatus, to: SchedulingStatus): IO[Seq[ScheduledVideoDownload]] = IO.pure(Seq.empty)
    override def updateDownloadProgress(id: String, downloadedBytes: Long, timestamp: Instant): IO[Option[ScheduledVideoDownload]] =
      IO.pure(updateDownloadProgressResult)
    override def deleteById(id: String): IO[Int] = IO.pure(1)
    override def search(
      term: Option[String],
      videoUrls: Option[NonEmptyList[Uri]],
      durationRange: RangeValue[FiniteDuration],
      sizeRange: RangeValue[Long],
      pageNumber: Int,
      pageSize: Int,
      sortBy: SortBy,
      order: Order,
      schedulingStatuses: Option[NonEmptyList[SchedulingStatus]],
      videoSites: Option[NonEmptyList[VideoSite]],
      maybeUserId: Option[String]
    ): IO[Seq[ScheduledVideoDownload]] = IO.pure(searchResult)
    override def retryErroredScheduledDownloads(maybeUserId: Option[String], timestamp: Instant): IO[Seq[ScheduledVideoDownload]] =
      IO.pure(retryErroredResult)
    override def staleTask(delay: FiniteDuration, timestamp: Instant): IO[Option[ScheduledVideoDownload]] = IO.pure(None)
    override def updateTimedOutTasks(timeout: FiniteDuration, timestamp: Instant): IO[Seq[ScheduledVideoDownload]] = IO.pure(Seq.empty)
    override def acquireTask(timestamp: Instant): IO[Option[ScheduledVideoDownload]] = IO.pure(None)
  }

  class StubVideoTitleDao(
    findResult: (String, String) => Option[VideoTitle] = (_, _) => None
  ) extends VideoTitleDao[IO] {
    override def insert(videoTitle: VideoTitle): IO[Int] = IO.pure(1)
    override def find(videoId: String, userId: String): IO[Option[VideoTitle]] = IO.pure(findResult(videoId, userId))
    override def update(videoId: String, userId: String, title: String): IO[Int] = IO.pure(1)
    override def delete(maybeVideoId: Option[String], maybeUserId: Option[String]): IO[Int] = IO.pure(1)
  }

  class StubVideoPermissionDao(
    findResult: (Option[String], Option[String]) => Seq[VideoPermission] = (_, _) => Seq.empty
  ) extends VideoPermissionDao[IO] {
    override def insert(videoPermission: VideoPermission): IO[Int] = IO.pure(1)
    override def find(userId: Option[String], scheduledVideoId: Option[String]): IO[Seq[VideoPermission]] =
      IO.pure(findResult(userId, scheduledVideoId))
    override def delete(maybeUserId: Option[String], maybeScheduledVideoId: Option[String]): IO[Int] = IO.pure(1)
  }

  private def createService(
    videoAnalysisService: VideoAnalysisService[IO] = new StubVideoAnalysisService(),
    scheduledVideoDownloadPublisher: StubPublisher[ScheduledVideoDownload] = new StubPublisher[ScheduledVideoDownload](),
    workerStatusPublisher: StubPublisher[WorkerStatusUpdate] = new StubPublisher[WorkerStatusUpdate](),
    configurationService: ConfigurationService[IO, ApiConfigKey] = new StubConfigurationService(),
    schedulingDao: SchedulingDao[IO] = new StubSchedulingDao(),
    videoTitleDao: VideoTitleDao[IO] = new StubVideoTitleDao(),
    videoPermissionDao: VideoPermissionDao[IO] = new StubVideoPermissionDao()
  )(implicit clock: Clock[IO]): (ApiSchedulingServiceImpl[IO, IO], StubPublisher[ScheduledVideoDownload], StubPublisher[WorkerStatusUpdate]) = {
    val service = new ApiSchedulingServiceImpl[IO, IO](
      videoAnalysisService,
      scheduledVideoDownloadPublisher,
      workerStatusPublisher,
      configurationService,
      schedulingDao,
      videoTitleDao,
      videoPermissionDao
    )
    (service, scheduledVideoDownloadPublisher, workerStatusPublisher)
  }

  // schedule tests
  "schedule" should "create new scheduled video download when video doesn't exist" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val videoUrl = uri"https://youtube.com/watch?v=abc123"
    val publisher = new StubPublisher[ScheduledVideoDownload]()

    val videoAnalysisService = new StubVideoAnalysisService(
      metadataResult = _ => IO.pure(NewlyCreated(sampleVideoMetadata))
    )

    val (service, _, _) = createService(
      videoAnalysisService = videoAnalysisService,
      scheduledVideoDownloadPublisher = publisher,
      schedulingDao = new StubSchedulingDao(searchResult = Seq.empty)
    )

    service.schedule(videoUrl, "user-1").map { result =>
      result mustBe a[ScheduledVideoResult.NewlyScheduled]
      result.isNew mustBe true
      publisher.publishedMessages.size mustBe 1
    }
  }

  it should "return AlreadyScheduled when video exists and user already has permission" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val videoUrl = uri"https://youtube.com/watch?v=abc123"
    val publisher = new StubPublisher[ScheduledVideoDownload]()

    val schedulingDao = new StubSchedulingDao(searchResult = Seq(sampleScheduledVideoDownload))
    val videoTitleDao = new StubVideoTitleDao(
      findResult = (videoId, userId) =>
        if (videoId == "video-1" && userId == "user-1") Some(VideoTitle(videoId, userId, "Sample Video")) else None
    )
    val videoPermissionDao = new StubVideoPermissionDao(
      findResult = (userId, videoId) =>
        if (userId.contains("user-1") && videoId.contains("video-1"))
          Seq(VideoPermission(timestamp, "video-1", "user-1"))
        else Seq.empty
    )

    val (service, _, _) = createService(
      scheduledVideoDownloadPublisher = publisher,
      schedulingDao = schedulingDao,
      videoTitleDao = videoTitleDao,
      videoPermissionDao = videoPermissionDao
    )

    service.schedule(videoUrl, "user-1").map { result =>
      result mustBe a[ScheduledVideoResult.AlreadyScheduled]
      result.isNew mustBe false
      result.scheduledVideoDownload mustBe sampleScheduledVideoDownload
    }
  }

  it should "return NewlyScheduled when video exists but user doesn't have permission" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val videoUrl = uri"https://youtube.com/watch?v=abc123"
    val publisher = new StubPublisher[ScheduledVideoDownload]()

    val schedulingDao = new StubSchedulingDao(searchResult = Seq(sampleScheduledVideoDownload))
    val videoTitleDao = new StubVideoTitleDao(findResult = (_, _) => None)
    val videoPermissionDao = new StubVideoPermissionDao(findResult = (_, _) => Seq.empty)

    val (service, _, _) = createService(
      scheduledVideoDownloadPublisher = publisher,
      schedulingDao = schedulingDao,
      videoTitleDao = videoTitleDao,
      videoPermissionDao = videoPermissionDao
    )

    service.schedule(videoUrl, "user-2").map { result =>
      result mustBe a[ScheduledVideoResult.NewlyScheduled]
      result.isNew mustBe true
      result.scheduledVideoDownload mustBe sampleScheduledVideoDownload
    }
  }

  it should "raise error when permissions exist but title doesn't (invalid state)" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val videoUrl = uri"https://youtube.com/watch?v=abc123"

    val schedulingDao = new StubSchedulingDao(searchResult = Seq(sampleScheduledVideoDownload))
    val videoTitleDao = new StubVideoTitleDao(findResult = (_, _) => None) // No title
    val videoPermissionDao = new StubVideoPermissionDao(
      findResult = (_, _) => Seq(VideoPermission(timestamp, "video-1", "user-1")) // Has permission
    )

    val (service, _, _) = createService(
      schedulingDao = schedulingDao,
      videoTitleDao = videoTitleDao,
      videoPermissionDao = videoPermissionDao
    )

    service.schedule(videoUrl, "user-1").error.map { error =>
      error mustBe an[InvalidConditionException]
      error.getMessage must include("invalid state")
    }
  }

  it should "raise error when title exists but permissions don't (invalid state)" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val videoUrl = uri"https://youtube.com/watch?v=abc123"

    val schedulingDao = new StubSchedulingDao(searchResult = Seq(sampleScheduledVideoDownload))
    val videoTitleDao = new StubVideoTitleDao(
      findResult = (videoId, userId) =>
        if (videoId == "video-1" && userId == "user-1") Some(VideoTitle(videoId, userId, "Sample Video")) else None
    )
    val videoPermissionDao = new StubVideoPermissionDao(findResult = (_, _) => Seq.empty) // No permission

    val (service, _, _) = createService(
      schedulingDao = schedulingDao,
      videoTitleDao = videoTitleDao,
      videoPermissionDao = videoPermissionDao
    )

    service.schedule(videoUrl, "user-1").error.map { error =>
      error mustBe an[InvalidConditionException]
      error.getMessage must include("invalid state")
    }
  }

  // retryFailed tests
  "retryFailed" should "retry failed downloads and publish to stream" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val failedDownload = sampleScheduledVideoDownload.copy(status = SchedulingStatus.Error)
    val publisher = new StubPublisher[ScheduledVideoDownload]()
    val schedulingDao = new StubSchedulingDao(retryErroredResult = Seq(failedDownload))

    val (service, _, _) = createService(
      scheduledVideoDownloadPublisher = publisher,
      schedulingDao = schedulingDao
    )

    service.retryFailed(Some("user-1")).map { result =>
      result mustBe Seq(failedDownload)
      publisher.publishedMessages.toList mustBe List(failedDownload)
    }
  }

  it should "return empty sequence when no failed downloads" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val publisher = new StubPublisher[ScheduledVideoDownload]()
    val schedulingDao = new StubSchedulingDao(retryErroredResult = Seq.empty)

    val (service, _, _) = createService(
      scheduledVideoDownloadPublisher = publisher,
      schedulingDao = schedulingDao
    )

    service.retryFailed(None).map { result =>
      result mustBe empty
      publisher.publishedMessages mustBe empty
    }
  }

  it should "handle multiple failed downloads" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val failedDownload1 = sampleScheduledVideoDownload.copy(videoMetadata = sampleVideoMetadata.copy(id = "video-1"))
    val failedDownload2 = sampleScheduledVideoDownload.copy(videoMetadata = sampleVideoMetadata.copy(id = "video-2"))

    val publisher = new StubPublisher[ScheduledVideoDownload]()
    val schedulingDao = new StubSchedulingDao(retryErroredResult = Seq(failedDownload1, failedDownload2))

    val (service, _, _) = createService(
      scheduledVideoDownloadPublisher = publisher,
      schedulingDao = schedulingDao
    )

    service.retryFailed(None).map { result =>
      result.size mustBe 2
      publisher.publishedMessages.size mustBe 2
    }
  }

  // search tests
  "search" should "return search results from dao" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val schedulingDao = new StubSchedulingDao(searchResult = Seq(sampleScheduledVideoDownload))
    val (service, _, _) = createService(schedulingDao = schedulingDao)

    service.search(
      Some("test"),
      None,
      RangeValue.all[FiniteDuration],
      RangeValue.all[Long],
      0,
      10,
      SortBy.Date,
      Order.Descending,
      None,
      None,
      None
    ).map { result =>
      result mustBe Seq(sampleScheduledVideoDownload)
    }
  }

  it should "raise error when sortBy is WatchTime" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val (service, _, _) = createService()

    service.search(
      None,
      None,
      RangeValue.all[FiniteDuration],
      RangeValue.all[Long],
      0,
      10,
      SortBy.WatchTime,
      Order.Descending,
      None,
      None,
      None
    ).error.map { error =>
      error mustBe an[IllegalArgumentException]
      error.getMessage must include("watch_time")
    }
  }

  it should "process video URLs before searching" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val schedulingDao = new StubSchedulingDao(searchResult = Seq(sampleScheduledVideoDownload))
    val (service, _, _) = createService(schedulingDao = schedulingDao)

    service.search(
      None,
      Some(NonEmptyList.of(uri"https://youtube.com/watch?v=abc123")),
      RangeValue.all[FiniteDuration],
      RangeValue.all[Long],
      0,
      10,
      SortBy.Date,
      Order.Descending,
      None,
      None,
      None
    ).map { result =>
      result mustBe Seq(sampleScheduledVideoDownload)
    }
  }

  it should "handle multiple video URLs and filters" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val schedulingDao = new StubSchedulingDao(searchResult = Seq(sampleScheduledVideoDownload))
    val (service, _, _) = createService(schedulingDao = schedulingDao)

    service.search(
      None,
      Some(NonEmptyList.of(
        uri"https://youtube.com/watch?v=abc123",
        uri"https://youtube.com/watch?v=def456"
      )),
      RangeValue.all[FiniteDuration],
      RangeValue.all[Long],
      0,
      10,
      SortBy.Title,
      Order.Ascending,
      Some(NonEmptyList.of(SchedulingStatus.Queued)),
      Some(NonEmptyList.of(VideoSite.YTDownloaderSite("youtube"))),
      Some("user-1")
    ).map { result =>
      result mustBe Seq(sampleScheduledVideoDownload)
    }
  }

  // updateSchedulingStatus tests
  "updateSchedulingStatus" should "update status and publish" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val updatedDownload = sampleScheduledVideoDownload.copy(status = SchedulingStatus.Active)
    val publisher = new StubPublisher[ScheduledVideoDownload]()
    val schedulingDao = new StubSchedulingDao(updateSchedulingStatusResult = Some(updatedDownload))

    val (service, _, _) = createService(
      scheduledVideoDownloadPublisher = publisher,
      schedulingDao = schedulingDao
    )

    service.updateSchedulingStatus("video-1", SchedulingStatus.Active).map { result =>
      result mustBe updatedDownload
      publisher.publishedMessages.toList mustBe List(updatedDownload)
    }
  }

  it should "raise ResourceNotFoundException when video not found" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val schedulingDao = new StubSchedulingDao(updateSchedulingStatusResult = None)
    val (service, _, _) = createService(schedulingDao = schedulingDao)

    service.updateSchedulingStatus("non-existent", SchedulingStatus.Active).error.map { error =>
      error mustBe a[ResourceNotFoundException]
    }
  }

  // getById tests
  "getById" should "return scheduled video download when found" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val schedulingDao = new StubSchedulingDao(
      getByIdResult = (id, _) => if (id == "video-1") Some(sampleScheduledVideoDownload) else None
    )
    val (service, _, _) = createService(schedulingDao = schedulingDao)

    service.getById("video-1", None).map { result =>
      result mustBe sampleScheduledVideoDownload
    }
  }

  it should "filter by user ID when provided" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val schedulingDao = new StubSchedulingDao(
      getByIdResult = (id, userId) =>
        if (id == "video-1" && userId.contains("user-1")) Some(sampleScheduledVideoDownload) else None
    )
    val (service, _, _) = createService(schedulingDao = schedulingDao)

    service.getById("video-1", Some("user-1")).map { result =>
      result mustBe sampleScheduledVideoDownload
    }
  }

  it should "raise ResourceNotFoundException when not found" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val schedulingDao = new StubSchedulingDao(getByIdResult = (_, _) => None)
    val (service, _, _) = createService(schedulingDao = schedulingDao)

    service.getById("non-existent", None).error.map { error =>
      error mustBe a[ResourceNotFoundException]
    }
  }

  // updateWorkerStatus tests
  "updateWorkerStatus" should "update config and publish status update" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val configStorage = mutable.Map.empty[String, WorkerStatus]
    val configService = new StubConfigurationService(configStorage)
    val workerStatusPublisher = new StubPublisher[WorkerStatusUpdate]()

    val (service, _, _) = createService(
      configurationService = configService,
      workerStatusPublisher = workerStatusPublisher
    )

    service.updateWorkerStatus(WorkerStatus.Paused).map { _ =>
      workerStatusPublisher.publishedMessages.toList mustBe List(WorkerStatusUpdate(WorkerStatus.Paused))
    }
  }

  // getWorkerStatus tests
  "getWorkerStatus" should "return configured worker status" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val configStorage: mutable.Map[String, WorkerStatus] = mutable.Map("worker-status" -> WorkerStatus.Paused)
    val configService = new StubConfigurationService(configStorage)

    val (service, _, _) = createService(configurationService = configService)

    service.getWorkerStatus.map { status =>
      status mustBe WorkerStatus.Paused
    }
  }

  it should "return Available as default when not configured" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val configService = new StubConfigurationService(mutable.Map.empty)
    val (service, _, _) = createService(configurationService = configService)

    service.getWorkerStatus.map { status =>
      status mustBe WorkerStatus.Available
    }
  }

  // updateDownloadProgress tests
  "updateDownloadProgress" should "update progress successfully" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val updatedDownload = sampleScheduledVideoDownload.copy(downloadedBytes = 1024)
    val schedulingDao = new StubSchedulingDao(updateDownloadProgressResult = Some(updatedDownload))

    val (service, _, _) = createService(schedulingDao = schedulingDao)

    service.updateDownloadProgress("video-1", timestamp, 1024).map { result =>
      result mustBe updatedDownload
    }
  }

  it should "fallback to getById when progress update returns None" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val schedulingDao = new StubSchedulingDao(
      updateDownloadProgressResult = None,
      getByIdResult = (id, _) => if (id == "video-1") Some(sampleScheduledVideoDownload) else None
    )

    val (service, _, _) = createService(schedulingDao = schedulingDao)

    service.updateDownloadProgress("video-1", timestamp, 1024).map { result =>
      result mustBe sampleScheduledVideoDownload
    }
  }

  // deleteById tests
  "deleteById" should "delete scheduled video download with user filter" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val schedulingDao = new StubSchedulingDao(
      getByIdResult = (id, userId) =>
        if (id == "video-1" && userId.contains("user-1")) Some(sampleScheduledVideoDownload) else None
    )

    val (service, _, _) = createService(schedulingDao = schedulingDao)

    service.deleteById("video-1", Some("user-1")).map { result =>
      result mustBe sampleScheduledVideoDownload
    }
  }

  it should "delete and publish for admin (no user ID)" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val publisher = new StubPublisher[ScheduledVideoDownload]()
    val schedulingDao = new StubSchedulingDao(
      getByIdResult = (id, _) => if (id == "video-1") Some(sampleScheduledVideoDownload) else None
    )

    val (service, _, _) = createService(
      scheduledVideoDownloadPublisher = publisher,
      schedulingDao = schedulingDao
    )

    service.deleteById("video-1", None).map { result =>
      result.status mustBe SchedulingStatus.Deleted
      publisher.publishedMessages.size mustBe 1
      publisher.publishedMessages.head.status mustBe SchedulingStatus.Deleted
    }
  }

  it should "raise ValidationException when trying to delete Completed video" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val completedDownload = sampleScheduledVideoDownload.copy(status = SchedulingStatus.Completed)
    val schedulingDao = new StubSchedulingDao(
      getByIdResult = (id, _) => if (id == "video-1") Some(completedDownload) else None
    )

    val (service, _, _) = createService(schedulingDao = schedulingDao)

    service.deleteById("video-1", None).error.map { error =>
      error mustBe a[ValidationException]
      error.getMessage must include("completed or downloaded")
    }
  }

  it should "raise ValidationException when trying to delete Downloaded video" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val downloadedDownload = sampleScheduledVideoDownload.copy(status = SchedulingStatus.Downloaded)
    val schedulingDao = new StubSchedulingDao(
      getByIdResult = (id, _) => if (id == "video-1") Some(downloadedDownload) else None
    )

    val (service, _, _) = createService(schedulingDao = schedulingDao)

    service.deleteById("video-1", None).error.map { error =>
      error mustBe a[ValidationException]
      error.getMessage must include("completed or downloaded")
    }
  }

  it should "raise ResourceNotFoundException when video not found" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val schedulingDao = new StubSchedulingDao(getByIdResult = (_, _) => None)
    val (service, _, _) = createService(schedulingDao = schedulingDao)

    service.deleteById("non-existent", None).error.map { error =>
      error mustBe a[ResourceNotFoundException]
    }
  }
}
