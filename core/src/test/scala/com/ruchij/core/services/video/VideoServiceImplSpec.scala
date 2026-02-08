package com.ruchij.core.services.video

import cats.data.NonEmptyList
import cats.effect.IO
import cats.~>
import com.ruchij.core.daos.permission.VideoPermissionDao
import com.ruchij.core.daos.permission.models.VideoPermission
import com.ruchij.core.daos.resource.FileResourceDao
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.scheduling.SchedulingDao
import com.ruchij.core.daos.scheduling.models.{RangeValue, ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.daos.snapshot.SnapshotDao
import com.ruchij.core.daos.snapshot.models.Snapshot
import com.ruchij.core.daos.title.VideoTitleDao
import com.ruchij.core.daos.title.models.VideoTitle
import com.ruchij.core.daos.video.VideoDao
import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.daos.videometadata.models.{VideoMetadata, VideoSite}
import com.ruchij.core.daos.videowatchhistory.VideoWatchHistoryDao
import com.ruchij.core.daos.videowatchhistory.models.{DetailedVideoWatchHistory, VideoWatchHistory}
import com.ruchij.core.exceptions.ResourceNotFoundException
import com.ruchij.core.services.models.{Order, SortBy}
import com.ruchij.core.services.repository.RepositoryService
import com.ruchij.core.test.IOSupport.{IOWrapper, runIO}
import com.ruchij.core.test.Providers
import com.ruchij.core.types.Clock
import com.ruchij.core.types.TimeUtils
import fs2.Stream
import org.http4s.MediaType
import org.http4s.implicits.http4sLiteralsSyntax
import java.time.Instant
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.collection.mutable
import scala.concurrent.duration._

class VideoServiceImplSpec extends AnyFlatSpec with Matchers {

  private val timestamp = TimeUtils.instantOf(2024, 5, 15, 10, 30)

  implicit val transaction: IO ~> IO = new (IO ~> IO) {
    override def apply[A](fa: IO[A]): IO[A] = fa
  }

  private val sampleFileResource =
    FileResource("file-resource-1", timestamp, "/videos/video1.mp4", MediaType.video.mp4, 1024 * 1024 * 100L)

  private val sampleThumbnail =
    FileResource("thumbnail-1", timestamp, "/thumbnails/thumb1.jpg", MediaType.image.jpeg, 50000L)

  private val sampleVideoMetadata = VideoMetadata(
    uri"https://example.com/video1",
    "video-1",
    VideoSite.YTDownloaderSite("youtube"),
    "Sample Video",
    10.minutes,
    1024 * 1024 * 100L,
    sampleThumbnail
  )

  private val sampleVideo = Video(sampleVideoMetadata, sampleFileResource, timestamp, 5.minutes)

  private val snapshotFileResource =
    FileResource("snapshot-file-1", timestamp, "/snapshots/snap1.jpg", MediaType.image.jpeg, 30000L)

  private val sampleSnapshot = Snapshot("video-1", snapshotFileResource, 2.minutes)

  class StubVideoDao(
    findByIdResult: (String, Option[String]) => Option[Video] = (_, _) => None,
    searchResult: Seq[Video] = Seq.empty,
    deleteByIdResult: Int = 1
  ) extends VideoDao[IO] {
    override def insert(
      videoMetadataId: String,
      videoFileResourceId: String,
      timestamp: Instant,
      watchTime: FiniteDuration
    ): IO[Int] =
      IO.pure(1)

    override def search(
      term: Option[String],
      videoUrls: Option[NonEmptyList[org.http4s.Uri]],
      durationRange: RangeValue[FiniteDuration],
      sizeRange: RangeValue[Long],
      pageNumber: Int,
      pageSize: Int,
      sortBy: SortBy,
      order: Order,
      videoSites: Option[NonEmptyList[VideoSite]],
      maybeUserId: Option[String]
    ): IO[Seq[Video]] = IO.pure(searchResult)

    override def incrementWatchTime(videoId: String, finiteDuration: FiniteDuration): IO[Option[FiniteDuration]] =
      IO.pure(None)

    override def findById(videoId: String, maybeUserId: Option[String]): IO[Option[Video]] =
      IO.pure(findByIdResult(videoId, maybeUserId))

    override def findByVideoFileResourceId(fileResourceId: String): IO[Option[Video]] =
      IO.pure(None)

    override def findByVideoPath(videoPath: String): IO[Option[Video]] =
      IO.pure(None)

    override def deleteById(videoId: String): IO[Int] =
      IO.pure(deleteByIdResult)

    override def hasVideoFilePermission(videoFileResourceId: String, userId: String): IO[Boolean] =
      IO.pure(false)

    override def isVideoFileResourceExist(videoFileResourceId: String): IO[Boolean] =
      IO.pure(false)

    override val count: IO[Int] = IO.pure(0)
    override val duration: IO[FiniteDuration] = IO.pure(0.seconds)
    override val size: IO[Long] = IO.pure(0L)
    override val sites: IO[Set[VideoSite]] = IO.pure(Set.empty)
  }

  class StubSnapshotDao(findByVideoResult: Seq[Snapshot] = Seq.empty) extends SnapshotDao[IO] {
    override def insert(snapshot: Snapshot): IO[Int] = IO.pure(1)
    override def findByVideo(videoId: String, maybeUserId: Option[String]): IO[Seq[Snapshot]] =
      IO.pure(findByVideoResult)
    override def hasPermission(snapshotFileResourceId: String, userId: String): IO[Boolean] = IO.pure(false)
    override def isSnapshotFileResource(fileResourceId: String): IO[Boolean] = IO.pure(false)
    override def deleteByVideo(videoId: String): IO[Int] = IO.pure(findByVideoResult.size)
  }

  class StubFileResourceDao extends FileResourceDao[IO] {
    override def insert(resource: FileResource): IO[Int] = IO.pure(1)
    override def update(id: String, size: Long): IO[Int] = IO.pure(1)
    override def getById(id: String): IO[Option[FileResource]] = IO.pure(None)
    override def findByPath(path: String): IO[Option[FileResource]] = IO.pure(None)
    override def deleteById(id: String): IO[Int] = IO.pure(1)
  }

  class StubVideoTitleDao extends VideoTitleDao[IO] {
    override def insert(videoTitle: VideoTitle): IO[Int] = IO.pure(1)
    override def find(videoId: String, userId: String): IO[Option[VideoTitle]] = IO.pure(None)
    override def update(videoId: String, userId: String, title: String): IO[Int] = IO.pure(1)
    override def delete(maybeVideoId: Option[String], maybeUserId: Option[String]): IO[Int] = IO.pure(1)
  }

  class StubVideoPermissionDao extends VideoPermissionDao[IO] {
    override def insert(videoPermission: VideoPermission): IO[Int] = IO.pure(1)
    override def find(userId: Option[String], scheduledVideoId: Option[String]): IO[Seq[VideoPermission]] =
      IO.pure(Seq.empty)
    override def delete(maybeUserId: Option[String], maybeScheduledVideoId: Option[String]): IO[Int] = IO.pure(1)
  }

  class StubSchedulingDao(updateSchedulingStatusResult: Option[ScheduledVideoDownload] = None)
      extends SchedulingDao[IO] {
    override def insert(scheduledVideoDownload: ScheduledVideoDownload): IO[Int] = IO.pure(1)
    override def getById(id: String, maybeUserId: Option[String]): IO[Option[ScheduledVideoDownload]] = IO.pure(None)
    override def markScheduledVideoDownloadAsComplete(
      id: String,
      timestamp: Instant
    ): IO[Option[ScheduledVideoDownload]] = IO.pure(None)
    override def updateSchedulingStatusById(
      id: String,
      status: SchedulingStatus,
      timestamp: Instant
    ): IO[Option[ScheduledVideoDownload]] =
      IO.pure(updateSchedulingStatusResult)
    override def setErrorById(
      id: String,
      throwable: Throwable,
      timestamp: Instant
    ): IO[Option[ScheduledVideoDownload]] = IO.pure(None)
    override def updateSchedulingStatus(from: SchedulingStatus, to: SchedulingStatus): IO[Seq[ScheduledVideoDownload]] =
      IO.pure(Seq.empty)
    override def updateDownloadProgress(
      id: String,
      downloadedBytes: Long,
      timestamp: Instant
    ): IO[Option[ScheduledVideoDownload]] = IO.pure(None)
    override def deleteById(id: String): IO[Int] = IO.pure(1)
    override def search(
      term: Option[String],
      videoUrls: Option[NonEmptyList[org.http4s.Uri]],
      durationRange: RangeValue[FiniteDuration],
      sizeRange: RangeValue[Long],
      pageNumber: Int,
      pageSize: Int,
      sortBy: SortBy,
      order: Order,
      schedulingStatuses: Option[NonEmptyList[SchedulingStatus]],
      videoSites: Option[NonEmptyList[VideoSite]],
      maybeUserId: Option[String]
    ): IO[Seq[ScheduledVideoDownload]] = IO.pure(Seq.empty)
    override def retryErroredScheduledDownloads(
      maybeUserId: Option[String],
      timestamp: Instant
    ): IO[Seq[ScheduledVideoDownload]] = IO.pure(Seq.empty)
    override def staleTask(delay: FiniteDuration, timestamp: Instant): IO[Option[ScheduledVideoDownload]] =
      IO.pure(None)
    override def updateTimedOutTasks(timeout: FiniteDuration, timestamp: Instant): IO[Seq[ScheduledVideoDownload]] =
      IO.pure(Seq.empty)
    override def acquireTask(timestamp: Instant): IO[Option[ScheduledVideoDownload]] = IO.pure(None)
  }

  class StubVideoWatchHistoryDao extends VideoWatchHistoryDao[IO] {
    override def insert(videoWatchHistory: VideoWatchHistory): IO[Unit] = IO.unit
    override def findBy(userId: String, pageSize: Int, pageNumber: Int): IO[List[DetailedVideoWatchHistory]] =
      IO.pure(List.empty)
    override def findBy(
      userId: String,
      videoId: String,
      pageSize: Int,
      pageNumber: Int
    ): IO[List[DetailedVideoWatchHistory]] = IO.pure(List.empty)
    override def findLastUpdatedAfter(
      userId: String,
      videoId: String,
      timestamp: Instant
    ): IO[Option[VideoWatchHistory]] = IO.pure(None)
    override def update(updatedVideoWatchHistory: VideoWatchHistory): IO[Unit] = IO.unit
    override def deleteBy(videoId: String): IO[Int] = IO.pure(1)
  }

  class StubRepositoryService(deleteResult: Boolean = true) extends RepositoryService[IO] {
    override type BackedType = String
    override def write(key: Key, data: Stream[IO, Byte]): Stream[IO, Nothing] = Stream.empty
    override def read(key: Key, start: Option[Long], end: Option[Long]): IO[Option[Stream[IO, Byte]]] = IO.pure(None)
    override def size(key: Key): IO[Option[Long]] = IO.pure(None)
    override def list(key: Key): Stream[IO, Key] = Stream.empty
    override def exists(key: Key): IO[Boolean] = IO.pure(false)
    override def backedType(key: Key): IO[BackedType] = IO.pure("file")
    override def delete(key: Key): IO[Boolean] = IO.pure(deleteResult)
    override def fileType(key: Key): IO[Option[MediaType]] = IO.pure(None)
  }

  private def createService(
    videoDao: VideoDao[IO],
    repositoryService: RepositoryService[IO] = new StubRepositoryService(),
    videoWatchHistoryDao: VideoWatchHistoryDao[IO] = new StubVideoWatchHistoryDao(),
    snapshotDao: SnapshotDao[IO] = new StubSnapshotDao(),
    fileResourceDao: FileResourceDao[IO] = new StubFileResourceDao(),
    videoTitleDao: VideoTitleDao[IO] = new StubVideoTitleDao(),
    videoPermissionDao: VideoPermissionDao[IO] = new StubVideoPermissionDao(),
    schedulingDao: SchedulingDao[IO] = new StubSchedulingDao()
  )(implicit clock: Clock[IO]): VideoServiceImpl[IO, IO] = {
    new VideoServiceImpl[IO, IO](
      repositoryService,
      videoDao,
      videoWatchHistoryDao,
      snapshotDao,
      fileResourceDao,
      videoTitleDao,
      videoPermissionDao,
      schedulingDao
    )
  }

  "findVideoById" should "return video when found" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val videoDao = new StubVideoDao(findByIdResult = (id, _) => if (id == "video-1") Some(sampleVideo) else None)

    val service = createService(videoDao = videoDao)

    service.findVideoById("video-1", None).map { video =>
      video mustBe sampleVideo
      video.id mustBe "video-1"
    }
  }

  it should "return video when found with user ID" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val videoDao = new StubVideoDao(
      findByIdResult = (id, userId) => if (id == "video-1" && userId.contains("user-1")) Some(sampleVideo) else None
    )

    val service = createService(videoDao = videoDao)

    service.findVideoById("video-1", Some("user-1")).map { video =>
      video mustBe sampleVideo
    }
  }

  it should "throw ResourceNotFoundException when video not found" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val videoDao = new StubVideoDao(findByIdResult = (_, _) => None)
    val service = createService(videoDao = videoDao)

    service.findVideoById("non-existent", None).error.map { error =>
      error mustBe a[ResourceNotFoundException]
      error.getMessage must include("non-existent")
    }
  }

  "deleteById" should "delete video and all associated resources with video file" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val deletedPaths = mutable.ListBuffer.empty[String]

    val videoDao = new StubVideoDao(findByIdResult = (id, _) => if (id == "video-1") Some(sampleVideo) else None)

    val snapshotDao = new StubSnapshotDao(findByVideoResult = Seq(sampleSnapshot))

    val repositoryService = new StubRepositoryService() {
      override def delete(key: Key): IO[Boolean] = IO.delay {
        deletedPaths += key
        true
      }
    }

    val service = createService(repositoryService = repositoryService, videoDao = videoDao, snapshotDao = snapshotDao)

    service.deleteById("video-1", deleteVideoFile = true).map { video =>
      video mustBe sampleVideo
      deletedPaths must contain(snapshotFileResource.path)
      deletedPaths must contain(sampleFileResource.path)
    }
  }

  it should "delete video but not the video file when deleteVideoFile is false" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val deletedPaths = mutable.ListBuffer.empty[String]

    val videoDao = new StubVideoDao(findByIdResult = (id, _) => if (id == "video-1") Some(sampleVideo) else None)

    val snapshotDao = new StubSnapshotDao(findByVideoResult = Seq(sampleSnapshot))

    val repositoryService = new StubRepositoryService() {
      override def delete(key: Key): IO[Boolean] = IO.delay {
        deletedPaths += key
        true
      }
    }

    val service = createService(repositoryService = repositoryService, videoDao = videoDao, snapshotDao = snapshotDao)

    service.deleteById("video-1", deleteVideoFile = false).map { video =>
      video mustBe sampleVideo
      deletedPaths must contain(snapshotFileResource.path)
      deletedPaths must not contain sampleFileResource.path
    }
  }

  it should "throw ResourceNotFoundException when video to delete not found" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val videoDao = new StubVideoDao(findByIdResult = (_, _) => None)
    val service = createService(videoDao = videoDao)

    service.deleteById("non-existent", deleteVideoFile = true).error.map { error =>
      error mustBe a[ResourceNotFoundException]
      error.getMessage must include("non-existent")
    }
  }

  it should "delete video with multiple snapshots" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val deletedPaths = mutable.ListBuffer.empty[String]

    val snapshotFile2 = FileResource("snapshot-file-2", timestamp, "/snapshots/snap2.jpg", MediaType.image.jpeg, 25000L)
    val snapshot2 = Snapshot("video-1", snapshotFile2, 5.minutes)

    val videoDao = new StubVideoDao(findByIdResult = (id, _) => if (id == "video-1") Some(sampleVideo) else None)

    val snapshotDao = new StubSnapshotDao(findByVideoResult = Seq(sampleSnapshot, snapshot2))

    val repositoryService = new StubRepositoryService() {
      override def delete(key: Key): IO[Boolean] = IO.delay {
        deletedPaths += key
        true
      }
    }

    val service = createService(repositoryService = repositoryService, videoDao = videoDao, snapshotDao = snapshotDao)

    service.deleteById("video-1", deleteVideoFile = true).map { video =>
      video mustBe sampleVideo
      deletedPaths must contain(snapshotFileResource.path)
      deletedPaths must contain(snapshotFile2.path)
      deletedPaths must contain(sampleFileResource.path)
    }
  }

  it should "delete video with no snapshots" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val deletedPaths = mutable.ListBuffer.empty[String]

    val videoDao = new StubVideoDao(findByIdResult = (id, _) => if (id == "video-1") Some(sampleVideo) else None)

    val snapshotDao = new StubSnapshotDao(findByVideoResult = Seq.empty)

    val repositoryService = new StubRepositoryService() {
      override def delete(key: Key): IO[Boolean] = IO.delay {
        deletedPaths += key
        true
      }
    }

    val service = createService(repositoryService = repositoryService, videoDao = videoDao, snapshotDao = snapshotDao)

    service.deleteById("video-1", deleteVideoFile = true).map { video =>
      video mustBe sampleVideo
      deletedPaths must contain(sampleFileResource.path)
      deletedPaths.size mustBe 1
    }
  }

  "queueIncorrectlyCompletedVideos" should "requeue small videos found in search" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val smallFileResource = sampleFileResource.copy(size = 500 * 1024L) // 500KB - under 1MB threshold
    val smallVideoMetadata = sampleVideoMetadata.copy(size = 500 * 1024L)
    val smallVideo = sampleVideo.copy(videoMetadata = smallVideoMetadata, fileResource = smallFileResource)

    val deletedPaths = mutable.ListBuffer.empty[String]

    val videoDao = new StubVideoDao(searchResult = Seq(smallVideo), findByIdResult = (_, _) => None)

    val snapshotDao = new StubSnapshotDao(findByVideoResult = Seq(sampleSnapshot))

    val repositoryService = new StubRepositoryService() {
      override def delete(key: Key): IO[Boolean] = IO.delay {
        deletedPaths += key
        true
      }
    }

    val service = createService(repositoryService = repositoryService, videoDao = videoDao, snapshotDao = snapshotDao)

    service.queueIncorrectlyCompletedVideos.map { videos =>
      videos.size mustBe 1
      videos.head mustBe smallVideo
      deletedPaths must contain(snapshotFileResource.path)
      deletedPaths must contain(smallFileResource.path)
    }
  }

  it should "return empty sequence when no incorrectly completed videos" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val videoDao = new StubVideoDao(searchResult = Seq.empty)
    val service = createService(videoDao = videoDao)

    service.queueIncorrectlyCompletedVideos.map { videos =>
      videos mustBe empty
    }
  }

  it should "process multiple incorrectly completed videos" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val smallFileResource1 = sampleFileResource.copy(id = "file-1", path = "/videos/small1.mp4", size = 300 * 1024L)
    val smallFileResource2 = sampleFileResource.copy(id = "file-2", path = "/videos/small2.mp4", size = 400 * 1024L)

    val smallVideoMetadata1 = sampleVideoMetadata.copy(id = "video-small-1", size = 300 * 1024L)
    val smallVideoMetadata2 = sampleVideoMetadata.copy(id = "video-small-2", size = 400 * 1024L)

    val smallVideo1 = Video(smallVideoMetadata1, smallFileResource1, timestamp, 0.seconds)
    val smallVideo2 = Video(smallVideoMetadata2, smallFileResource2, timestamp, 0.seconds)

    val deletedPaths = mutable.ListBuffer.empty[String]

    val videoDao = new StubVideoDao(searchResult = Seq(smallVideo1, smallVideo2))
    val snapshotDao = new StubSnapshotDao(findByVideoResult = Seq.empty)

    val repositoryService = new StubRepositoryService() {
      override def delete(key: Key): IO[Boolean] = IO.delay {
        deletedPaths += key
        true
      }
    }

    val service = createService(repositoryService = repositoryService, videoDao = videoDao, snapshotDao = snapshotDao)

    service.queueIncorrectlyCompletedVideos.map { videos =>
      videos.size mustBe 2
      videos must contain(smallVideo1)
      videos must contain(smallVideo2)
      deletedPaths must contain(smallFileResource1.path)
      deletedPaths must contain(smallFileResource2.path)
    }
  }

  it should "delete snapshots before requeuing videos" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val smallFileResource = sampleFileResource.copy(size = 500 * 1024L)
    val smallVideoMetadata = sampleVideoMetadata.copy(size = 500 * 1024L)
    val smallVideo = sampleVideo.copy(videoMetadata = smallVideoMetadata, fileResource = smallFileResource)

    val snapshotFile2 = FileResource("snapshot-file-2", timestamp, "/snapshots/snap2.jpg", MediaType.image.jpeg, 25000L)
    val snapshot2 = Snapshot("video-1", snapshotFile2, 5.minutes)

    val deletedPaths = mutable.ListBuffer.empty[String]

    val videoDao = new StubVideoDao(searchResult = Seq(smallVideo))
    val snapshotDao = new StubSnapshotDao(findByVideoResult = Seq(sampleSnapshot, snapshot2))

    val repositoryService = new StubRepositoryService() {
      override def delete(key: Key): IO[Boolean] = IO.delay {
        deletedPaths += key
        true
      }
    }

    val service = createService(repositoryService = repositoryService, videoDao = videoDao, snapshotDao = snapshotDao)

    service.queueIncorrectlyCompletedVideos.map { videos =>
      videos.size mustBe 1
      deletedPaths must contain(snapshotFileResource.path)
      deletedPaths must contain(snapshotFile2.path)
      deletedPaths must contain(smallFileResource.path)
    }
  }
}
