package com.ruchij.api.services.video

import cats.data.NonEmptyList
import cats.effect.IO
import cats.~>
import com.ruchij.core.commands.ScanVideosCommand
import com.ruchij.core.daos.permission.VideoPermissionDao
import com.ruchij.core.daos.permission.models.VideoPermission
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.scheduling.models.RangeValue
import com.ruchij.core.daos.snapshot.SnapshotDao
import com.ruchij.core.daos.snapshot.models.Snapshot
import com.ruchij.core.daos.title.VideoTitleDao
import com.ruchij.core.daos.title.models.VideoTitle
import com.ruchij.core.daos.video.VideoDao
import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.daos.videometadata.VideoMetadataDao
import com.ruchij.core.daos.videometadata.models.{CustomVideoSite, VideoMetadata, VideoSite}
import com.ruchij.core.daos.workers.models.VideoScan
import com.ruchij.core.daos.workers.models.VideoScan.ScanStatus
import com.ruchij.core.kv.codecs.{KVCodec, KVDecoder}
import com.ruchij.core.messaging.Publisher
import com.ruchij.core.services.config.ConfigurationService
import com.ruchij.core.services.config.models.SharedConfigKey
import com.ruchij.core.services.models.{Order, SortBy}
import com.ruchij.core.services.video.VideoService
import com.ruchij.core.services.video.models.VideoServiceSummary
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.test.Providers
import com.ruchij.core.types.{Clock, TimeUtils}
import fs2.Pipe
import org.http4s.{MediaType, Uri}
import org.http4s.implicits.http4sLiteralsSyntax
import java.time.Instant
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration._
import scala.language.postfixOps

class ApiVideoServiceImplSpec extends AnyFlatSpec with Matchers {

  private val timestamp = TimeUtils.instantOf(2024, 5, 15, 10, 30)

  private val sampleFileResource = FileResource(
    "file-resource-1",
    timestamp,
    "/videos/video1.mp4",
    MediaType.video.mp4,
    1000000L
  )

  private val sampleThumbnail = FileResource(
    "thumbnail-1",
    timestamp,
    "/thumbnails/thumb1.jpg",
    MediaType.image.jpeg,
    5000L
  )

  private val sampleVideoMetadata = VideoMetadata(
    uri"https://example.com/video1",
    "video-1",
    CustomVideoSite.SpankBang,
    "Sample Video",
    10 minutes,
    1000000L,
    sampleThumbnail
  )

  private val sampleVideo = Video(
    sampleVideoMetadata,
    sampleFileResource,
    timestamp,
    5 minutes
  )

  private val sampleSnapshot = Snapshot(
    "video-1",
    FileResource("snapshot-1", timestamp, "/snapshots/snap1.jpg", MediaType.image.jpeg, 3000L),
    2 minutes
  )

  private def createStubConfigService(
    initialValue: Option[VideoScan] = None
  ): (ConfigurationService[IO, SharedConfigKey], AtomicReference[Option[VideoScan]]) = {
    val stateRef = new AtomicReference[Option[VideoScan]](initialValue)
    val service = new ConfigurationService[IO, SharedConfigKey] {
      override def get[A: KVDecoder[IO, *], K[_] <: SharedConfigKey[_]](key: K[A]): IO[Option[A]] =
        IO.pure(stateRef.get().asInstanceOf[Option[A]])

      override def put[A: KVCodec[IO, *], K[_] <: SharedConfigKey[_]](key: K[A], value: A): IO[Option[A]] = {
        val old = stateRef.getAndSet(Some(value.asInstanceOf[VideoScan]))
        IO.pure(old.asInstanceOf[Option[A]])
      }

      override def delete[A: KVDecoder[IO, *], K[_] <: SharedConfigKey[_]](key: K[A]): IO[Option[A]] = {
        val old = stateRef.getAndSet(None)
        IO.pure(old.asInstanceOf[Option[A]])
      }
    }
    (service, stateRef)
  }

  private def createStubVideoService(
    findVideoByIdResult: (String, Option[String]) => IO[Video] = (_, _) => IO.raiseError(new RuntimeException("Not implemented")),
    deleteByIdResult: (String, Boolean) => IO[Video] = (_, _) => IO.raiseError(new RuntimeException("Not implemented")),
    queueIncorrectlyCompletedResult: IO[Seq[Video]] = IO.pure(Seq.empty)
  ): VideoService[IO, IO] = new VideoService[IO, IO] {
    override def findVideoById(videoId: String, maybeUserId: Option[String]): IO[Video] =
      findVideoByIdResult(videoId, maybeUserId)
    override def deleteById(videoId: String, deleteVideoFile: Boolean): IO[Video] =
      deleteByIdResult(videoId, deleteVideoFile)
    override val queueIncorrectlyCompletedVideos: IO[Seq[Video]] = queueIncorrectlyCompletedResult
  }

  private def createStubVideoDao(
    searchResult: Seq[Video] = Seq.empty,
    countResult: Int = 0,
    sizeResult: Long = 0L,
    durationResult: FiniteDuration = 0 seconds,
    sitesResult: Set[VideoSite] = Set.empty
  ): VideoDao[IO] = new VideoDao[IO] {
    override def insert(videoMetadataId: String, videoFileResourceId: String, timestamp: Instant, watchTime: FiniteDuration): IO[Int] = IO.pure(1)
    override def search(term: Option[String], videoUrls: Option[NonEmptyList[Uri]], durationRange: RangeValue[FiniteDuration], sizeRange: RangeValue[Long], pageNumber: Int, pageSize: Int, sortBy: SortBy, order: Order, videoSites: Option[NonEmptyList[VideoSite]], maybeUserId: Option[String]): IO[Seq[Video]] = IO.pure(searchResult)
    override def incrementWatchTime(videoId: String, finiteDuration: FiniteDuration): IO[Option[FiniteDuration]] = IO.pure(None)
    override def findById(videoId: String, maybeUserId: Option[String]): IO[Option[Video]] = IO.pure(None)
    override def findByVideoFileResourceId(fileResourceId: String): IO[Option[Video]] = IO.pure(None)
    override def findByVideoPath(videoPath: String): IO[Option[Video]] = IO.pure(None)
    override def deleteById(videoId: String): IO[Int] = IO.pure(1)
    override def hasVideoFilePermission(videoFileResourceId: String, userId: String): IO[Boolean] = IO.pure(true)
    override val count: IO[Int] = IO.pure(countResult)
    override val duration: IO[FiniteDuration] = IO.pure(durationResult)
    override val size: IO[Long] = IO.pure(sizeResult)
    override val sites: IO[Set[VideoSite]] = IO.pure(sitesResult)
  }

  private def createStubVideoMetadataDao(
    updateResult: Int = 1
  ): VideoMetadataDao[IO] = new VideoMetadataDao[IO] {
    override def insert(videoMetadata: VideoMetadata): IO[Int] = IO.pure(1)
    override def update(videoMetadataId: String, title: Option[String], size: Option[Long], maybeDuration: Option[FiniteDuration]): IO[Int] = IO.pure(updateResult)
    override def findById(videoMetadataId: String): IO[Option[VideoMetadata]] = IO.pure(None)
    override def findByUrl(uri: Uri): IO[Option[VideoMetadata]] = IO.pure(None)
    override def deleteById(videoMetadataId: String): IO[Int] = IO.pure(1)
  }

  private def createStubSnapshotDao(
    findByVideoResult: Seq[Snapshot] = Seq.empty
  ): SnapshotDao[IO] = new SnapshotDao[IO] {
    override def insert(snapshot: Snapshot): IO[Int] = IO.pure(1)
    override def findByVideo(videoId: String, maybeUserId: Option[String]): IO[Seq[Snapshot]] = IO.pure(findByVideoResult)
    override def hasPermission(snapshotFileResourceId: String, userId: String): IO[Boolean] = IO.pure(true)
    override def deleteByVideo(videoId: String): IO[Int] = IO.pure(1)
  }

  private def createStubVideoTitleDao(
    updateResult: Int = 1,
    deleteResult: Int = 1
  ): VideoTitleDao[IO] = new VideoTitleDao[IO] {
    override def insert(videoTitle: VideoTitle): IO[Int] = IO.pure(1)
    override def find(videoId: String, userId: String): IO[Option[VideoTitle]] = IO.pure(None)
    override def update(videoId: String, userId: String, title: String): IO[Int] = IO.pure(updateResult)
    override def delete(maybeVideoId: Option[String], maybeUserId: Option[String]): IO[Int] = IO.pure(deleteResult)
  }

  private def createStubVideoPermissionDao(
    deleteResult: Int = 1
  ): VideoPermissionDao[IO] = new VideoPermissionDao[IO] {
    override def insert(videoPermission: VideoPermission): IO[Int] = IO.pure(1)
    override def find(userId: Option[String], scheduledVideoId: Option[String]): IO[Seq[VideoPermission]] = IO.pure(Seq.empty)
    override def delete(maybeUserId: Option[String], maybeScheduledVideoId: Option[String]): IO[Int] = IO.pure(deleteResult)
  }

  private def createStubPublisher(
    publishOneResult: IO[Unit] = IO.unit
  ): Publisher[IO, ScanVideosCommand] = new Publisher[IO, ScanVideosCommand] {
    override val publish: Pipe[IO, ScanVideosCommand, Unit] = _.evalMap(_ => publishOneResult)
    override def publishOne(input: ScanVideosCommand): IO[Unit] = publishOneResult
  }

  private def createService(
    videoService: VideoService[IO, IO],
    videoScanPublisher: Publisher[IO, ScanVideosCommand],
    sharedConfigService: ConfigurationService[IO, SharedConfigKey],
    videoDao: VideoDao[IO],
    videoMetadataDao: VideoMetadataDao[IO],
    snapshotDao: SnapshotDao[IO],
    videoTitleDao: VideoTitleDao[IO],
    videoPermissionDao: VideoPermissionDao[IO]
  )(implicit clock: Clock[IO]): ApiVideoServiceImpl[IO, IO] = {
    implicit val transaction: IO ~> IO = new (IO ~> IO) {
      override def apply[A](fa: IO[A]): IO[A] = fa
    }

    new ApiVideoServiceImpl[IO, IO](
      videoService,
      videoScanPublisher,
      sharedConfigService,
      videoDao,
      videoMetadataDao,
      snapshotDao,
      videoTitleDao,
      videoPermissionDao
    )
  }

  "fetchById" should "fetch video by ID without user ID" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    val (sharedConfigService, _) = createStubConfigService()

    val videoService = createStubVideoService(
      findVideoByIdResult = (id, userId) => {
        if (id == "video-1" && userId.isEmpty) IO.pure(sampleVideo)
        else IO.raiseError(new RuntimeException("Video not found"))
      }
    )

    val apiVideoService = createService(
      videoService,
      createStubPublisher(),
      sharedConfigService,
      createStubVideoDao(),
      createStubVideoMetadataDao(),
      createStubSnapshotDao(),
      createStubVideoTitleDao(),
      createStubVideoPermissionDao()
    )

    apiVideoService.fetchById("video-1", None).map { video =>
      video mustBe sampleVideo
    }
  }

  it should "fetch video by ID with user ID" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    val (sharedConfigService, _) = createStubConfigService()

    val videoService = createStubVideoService(
      findVideoByIdResult = (id, userId) => {
        if (id == "video-1" && userId.contains("user-1")) IO.pure(sampleVideo)
        else IO.raiseError(new RuntimeException("Video not found"))
      }
    )

    val apiVideoService = createService(
      videoService,
      createStubPublisher(),
      sharedConfigService,
      createStubVideoDao(),
      createStubVideoMetadataDao(),
      createStubSnapshotDao(),
      createStubVideoTitleDao(),
      createStubVideoPermissionDao()
    )

    apiVideoService.fetchById("video-1", Some("user-1")).map { video =>
      video mustBe sampleVideo
    }
  }

  "fetchVideoSnapshots" should "fetch snapshots for a video without user ID" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    val (sharedConfigService, _) = createStubConfigService()

    val apiVideoService = createService(
      createStubVideoService(),
      createStubPublisher(),
      sharedConfigService,
      createStubVideoDao(),
      createStubVideoMetadataDao(),
      createStubSnapshotDao(findByVideoResult = Seq(sampleSnapshot)),
      createStubVideoTitleDao(),
      createStubVideoPermissionDao()
    )

    apiVideoService.fetchVideoSnapshots("video-1", None).map { result =>
      result mustBe Seq(sampleSnapshot)
    }
  }

  it should "fetch snapshots for a video with user ID" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    val (sharedConfigService, _) = createStubConfigService()

    val apiVideoService = createService(
      createStubVideoService(),
      createStubPublisher(),
      sharedConfigService,
      createStubVideoDao(),
      createStubVideoMetadataDao(),
      createStubSnapshotDao(findByVideoResult = Seq(sampleSnapshot)),
      createStubVideoTitleDao(),
      createStubVideoPermissionDao()
    )

    apiVideoService.fetchVideoSnapshots("video-1", Some("user-1")).map { result =>
      result mustBe Seq(sampleSnapshot)
    }
  }

  "update" should "update video metadata title when no user ID provided (admin update)" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    val (sharedConfigService, _) = createStubConfigService()

    val updatedVideo = sampleVideo.copy(
      videoMetadata = sampleVideoMetadata.copy(title = "Updated Title")
    )

    var callCount = 0
    val videoService = createStubVideoService(
      findVideoByIdResult = (id, userId) => {
        callCount += 1
        if (id == "video-1" && userId.isEmpty) {
          if (callCount == 1) IO.pure(sampleVideo)
          else IO.pure(updatedVideo)
        } else IO.raiseError(new RuntimeException("Video not found"))
      }
    )

    val apiVideoService = createService(
      videoService,
      createStubPublisher(),
      sharedConfigService,
      createStubVideoDao(),
      createStubVideoMetadataDao(updateResult = 1),
      createStubSnapshotDao(),
      createStubVideoTitleDao(),
      createStubVideoPermissionDao()
    )

    apiVideoService.update("video-1", "Updated Title", None).map { video =>
      video mustBe updatedVideo
    }
  }

  it should "update user-specific video title when user ID provided" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    val (sharedConfigService, _) = createStubConfigService()

    val updatedVideo = sampleVideo.copy(
      videoMetadata = sampleVideoMetadata.copy(title = "User Updated Title")
    )

    var callCount = 0
    val videoService = createStubVideoService(
      findVideoByIdResult = (id, userId) => {
        callCount += 1
        if (id == "video-1" && userId.contains("user-1")) {
          if (callCount == 1) IO.pure(sampleVideo)
          else IO.pure(updatedVideo)
        } else IO.raiseError(new RuntimeException("Video not found"))
      }
    )

    val apiVideoService = createService(
      videoService,
      createStubPublisher(),
      sharedConfigService,
      createStubVideoDao(),
      createStubVideoMetadataDao(),
      createStubSnapshotDao(),
      createStubVideoTitleDao(updateResult = 1),
      createStubVideoPermissionDao()
    )

    apiVideoService.update("video-1", "User Updated Title", Some("user-1")).map { video =>
      video mustBe updatedVideo
    }
  }

  "deleteById" should "delete video for admin (no user ID)" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    val (sharedConfigService, _) = createStubConfigService()

    val videoService = createStubVideoService(
      deleteByIdResult = (id, deleteFile) => {
        if (id == "video-1" && deleteFile) IO.pure(sampleVideo)
        else IO.raiseError(new RuntimeException("Delete failed"))
      }
    )

    val apiVideoService = createService(
      videoService,
      createStubPublisher(),
      sharedConfigService,
      createStubVideoDao(),
      createStubVideoMetadataDao(),
      createStubSnapshotDao(),
      createStubVideoTitleDao(),
      createStubVideoPermissionDao()
    )

    apiVideoService.deleteById("video-1", None, deleteVideoFile = true).map { video =>
      video mustBe sampleVideo
    }
  }

  it should "delete user-specific associations when user ID provided" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    val (sharedConfigService, _) = createStubConfigService()

    val videoService = createStubVideoService(
      findVideoByIdResult = (id, userId) => {
        if (id == "video-1" && userId.contains("user-1")) IO.pure(sampleVideo)
        else IO.raiseError(new RuntimeException("Video not found"))
      }
    )

    val apiVideoService = createService(
      videoService,
      createStubPublisher(),
      sharedConfigService,
      createStubVideoDao(),
      createStubVideoMetadataDao(),
      createStubSnapshotDao(),
      createStubVideoTitleDao(deleteResult = 1),
      createStubVideoPermissionDao(deleteResult = 1)
    )

    apiVideoService.deleteById("video-1", Some("user-1"), deleteVideoFile = false).map { video =>
      video mustBe sampleVideo
    }
  }

  it should "delete video without deleting file" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    val (sharedConfigService, _) = createStubConfigService()

    val videoService = createStubVideoService(
      deleteByIdResult = (id, deleteFile) => {
        if (id == "video-1" && !deleteFile) IO.pure(sampleVideo)
        else IO.raiseError(new RuntimeException("Delete failed"))
      }
    )

    val apiVideoService = createService(
      videoService,
      createStubPublisher(),
      sharedConfigService,
      createStubVideoDao(),
      createStubVideoMetadataDao(),
      createStubSnapshotDao(),
      createStubVideoTitleDao(),
      createStubVideoPermissionDao()
    )

    apiVideoService.deleteById("video-1", None, deleteVideoFile = false).map { video =>
      video mustBe sampleVideo
    }
  }

  "search" should "search videos without video URLs" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    val (sharedConfigService, _) = createStubConfigService()

    val apiVideoService = createService(
      createStubVideoService(),
      createStubPublisher(),
      sharedConfigService,
      createStubVideoDao(searchResult = Seq(sampleVideo)),
      createStubVideoMetadataDao(),
      createStubSnapshotDao(),
      createStubVideoTitleDao(),
      createStubVideoPermissionDao()
    )

    apiVideoService.search(
      Some("test"),
      None,
      RangeValue.all[FiniteDuration],
      RangeValue.all[Long],
      0,
      10,
      SortBy.Date,
      Order.Descending,
      None,
      None
    ).map { result =>
      result mustBe Seq(sampleVideo)
    }
  }

  it should "search videos with video URLs" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    val (sharedConfigService, _) = createStubConfigService()

    val apiVideoService = createService(
      createStubVideoService(),
      createStubPublisher(),
      sharedConfigService,
      createStubVideoDao(searchResult = Seq(sampleVideo)),
      createStubVideoMetadataDao(),
      createStubSnapshotDao(),
      createStubVideoTitleDao(),
      createStubVideoPermissionDao()
    )

    apiVideoService.search(
      None,
      Some(NonEmptyList.of(uri"https://example.com/video1")),
      RangeValue.all[FiniteDuration],
      RangeValue.all[Long],
      0,
      10,
      SortBy.Date,
      Order.Descending,
      None,
      Some("user-1")
    ).map { result =>
      result mustBe Seq(sampleVideo)
    }
  }

  it should "search videos with site filter" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    val (sharedConfigService, _) = createStubConfigService()

    val apiVideoService = createService(
      createStubVideoService(),
      createStubPublisher(),
      sharedConfigService,
      createStubVideoDao(searchResult = Seq(sampleVideo)),
      createStubVideoMetadataDao(),
      createStubSnapshotDao(),
      createStubVideoTitleDao(),
      createStubVideoPermissionDao()
    )

    apiVideoService.search(
      None,
      None,
      RangeValue.all[FiniteDuration],
      RangeValue.all[Long],
      0,
      20,
      SortBy.Title,
      Order.Ascending,
      Some(NonEmptyList.of(CustomVideoSite.SpankBang)),
      None
    ).map { result =>
      result mustBe Seq(sampleVideo)
    }
  }

  it should "search videos with duration and size ranges" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    val (sharedConfigService, _) = createStubConfigService()

    val apiVideoService = createService(
      createStubVideoService(),
      createStubPublisher(),
      sharedConfigService,
      createStubVideoDao(searchResult = Seq(sampleVideo)),
      createStubVideoMetadataDao(),
      createStubSnapshotDao(),
      createStubVideoTitleDao(),
      createStubVideoPermissionDao()
    )

    apiVideoService.search(
      Some("search term"),
      None,
      RangeValue(Some(5 minutes), Some(30 minutes)),
      RangeValue(Some(100000L), Some(10000000L)),
      1,
      15,
      SortBy.Size,
      Order.Descending,
      None,
      None
    ).map { result =>
      result mustBe Seq(sampleVideo)
    }
  }

  "summary" should "return video service summary" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    val (sharedConfigService, _) = createStubConfigService()

    val sites: Set[VideoSite] = Set(CustomVideoSite.SpankBang, CustomVideoSite.PornOne)

    val apiVideoService = createService(
      createStubVideoService(),
      createStubPublisher(),
      sharedConfigService,
      createStubVideoDao(
        countResult = 100,
        sizeResult = 5000000000L,
        durationResult = 500 hours,
        sitesResult = sites
      ),
      createStubVideoMetadataDao(),
      createStubSnapshotDao(),
      createStubVideoTitleDao(),
      createStubVideoPermissionDao()
    )

    apiVideoService.summary.map { summary =>
      summary mustBe VideoServiceSummary(100, 5000000000L, 500 hours, sites)
    }
  }

  "scanForVideos" should "return existing scan when already in progress" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    val existingScan = VideoScan(timestamp, ScanStatus.InProgress)
    val (sharedConfigService, _) = createStubConfigService(Some(existingScan))

    val apiVideoService = createService(
      createStubVideoService(),
      createStubPublisher(),
      sharedConfigService,
      createStubVideoDao(),
      createStubVideoMetadataDao(),
      createStubSnapshotDao(),
      createStubVideoTitleDao(),
      createStubVideoPermissionDao()
    )

    apiVideoService.scanForVideos.map { scan =>
      scan mustBe existingScan
    }
  }

  it should "schedule new scan when no scan in progress" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    val (sharedConfigService, stateRef) = createStubConfigService(None)
    val expectedScan = VideoScan(timestamp, ScanStatus.Scheduled)

    val apiVideoService = createService(
      createStubVideoService(),
      createStubPublisher(),
      sharedConfigService,
      createStubVideoDao(),
      createStubVideoMetadataDao(),
      createStubSnapshotDao(),
      createStubVideoTitleDao(),
      createStubVideoPermissionDao()
    )

    apiVideoService.scanForVideos.map { scan =>
      scan mustBe expectedScan
      stateRef.get() mustBe Some(expectedScan)
    }
  }

  it should "schedule new scan when previous scan is not in progress (Idle)" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    val existingScan = VideoScan(timestamp.minus(java.time.Duration.ofHours(1)), ScanStatus.Idle)
    val (sharedConfigService, stateRef) = createStubConfigService(Some(existingScan))
    val expectedScan = VideoScan(timestamp, ScanStatus.Scheduled)

    val apiVideoService = createService(
      createStubVideoService(),
      createStubPublisher(),
      sharedConfigService,
      createStubVideoDao(),
      createStubVideoMetadataDao(),
      createStubSnapshotDao(),
      createStubVideoTitleDao(),
      createStubVideoPermissionDao()
    )

    apiVideoService.scanForVideos.map { scan =>
      scan mustBe expectedScan
      stateRef.get() mustBe Some(expectedScan)
    }
  }

  it should "schedule new scan when previous scan had error status" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    val existingScan = VideoScan(timestamp.minus(java.time.Duration.ofHours(1)), ScanStatus.Error)
    val (sharedConfigService, stateRef) = createStubConfigService(Some(existingScan))
    val expectedScan = VideoScan(timestamp, ScanStatus.Scheduled)

    val apiVideoService = createService(
      createStubVideoService(),
      createStubPublisher(),
      sharedConfigService,
      createStubVideoDao(),
      createStubVideoMetadataDao(),
      createStubSnapshotDao(),
      createStubVideoTitleDao(),
      createStubVideoPermissionDao()
    )

    apiVideoService.scanForVideos.map { scan =>
      scan mustBe expectedScan
      stateRef.get() mustBe Some(expectedScan)
    }
  }

  it should "schedule new scan when previous scan was scheduled (not in progress)" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    val existingScan = VideoScan(timestamp.minus(java.time.Duration.ofHours(1)), ScanStatus.Scheduled)
    val (sharedConfigService, stateRef) = createStubConfigService(Some(existingScan))
    val expectedScan = VideoScan(timestamp, ScanStatus.Scheduled)

    val apiVideoService = createService(
      createStubVideoService(),
      createStubPublisher(),
      sharedConfigService,
      createStubVideoDao(),
      createStubVideoMetadataDao(),
      createStubSnapshotDao(),
      createStubVideoTitleDao(),
      createStubVideoPermissionDao()
    )

    apiVideoService.scanForVideos.map { scan =>
      scan mustBe expectedScan
      stateRef.get() mustBe Some(expectedScan)
    }
  }

  "scanStatus" should "return current scan status" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    val existingScan = VideoScan(timestamp, ScanStatus.InProgress)
    val (sharedConfigService, _) = createStubConfigService(Some(existingScan))

    val apiVideoService = createService(
      createStubVideoService(),
      createStubPublisher(),
      sharedConfigService,
      createStubVideoDao(),
      createStubVideoMetadataDao(),
      createStubSnapshotDao(),
      createStubVideoTitleDao(),
      createStubVideoPermissionDao()
    )

    apiVideoService.scanStatus.map { status =>
      status mustBe Some(existingScan)
    }
  }

  it should "return None when no scan status exists" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    val (sharedConfigService, _) = createStubConfigService(None)

    val apiVideoService = createService(
      createStubVideoService(),
      createStubPublisher(),
      sharedConfigService,
      createStubVideoDao(),
      createStubVideoMetadataDao(),
      createStubSnapshotDao(),
      createStubVideoTitleDao(),
      createStubVideoPermissionDao()
    )

    apiVideoService.scanStatus.map { status =>
      status mustBe None
    }
  }

  "queueIncorrectlyCompletedVideos" should "delegate to video service" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    val (sharedConfigService, _) = createStubConfigService()

    val videoService = createStubVideoService(
      queueIncorrectlyCompletedResult = IO.pure(Seq(sampleVideo))
    )

    val apiVideoService = createService(
      videoService,
      createStubPublisher(),
      sharedConfigService,
      createStubVideoDao(),
      createStubVideoMetadataDao(),
      createStubSnapshotDao(),
      createStubVideoTitleDao(),
      createStubVideoPermissionDao()
    )

    apiVideoService.queueIncorrectlyCompletedVideos.map { result =>
      result mustBe Seq(sampleVideo)
    }
  }

  it should "return empty sequence when no incorrectly completed videos" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    val (sharedConfigService, _) = createStubConfigService()

    val apiVideoService = createService(
      createStubVideoService(queueIncorrectlyCompletedResult = IO.pure(Seq.empty)),
      createStubPublisher(),
      sharedConfigService,
      createStubVideoDao(),
      createStubVideoMetadataDao(),
      createStubSnapshotDao(),
      createStubVideoTitleDao(),
      createStubVideoPermissionDao()
    )

    apiVideoService.queueIncorrectlyCompletedVideos.map { result =>
      result mustBe Seq.empty
    }
  }
}
