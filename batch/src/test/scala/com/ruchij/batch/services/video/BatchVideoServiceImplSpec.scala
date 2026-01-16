package com.ruchij.batch.services.video

import cats.effect.IO
import cats.~>
import com.ruchij.core.daos.resource.FileResourceDao
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.video.VideoDao
import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.daos.videometadata.VideoMetadataDao
import com.ruchij.core.daos.videometadata.models.{VideoMetadata, VideoSite}
import com.ruchij.core.exceptions.{InvalidConditionException, ResourceNotFoundException}
import com.ruchij.core.services.video.VideoService
import com.ruchij.core.test.IOSupport.{IOWrapper, runIO}
import com.ruchij.core.test.Providers
import com.ruchij.core.types.JodaClock
import org.http4s.MediaType
import org.http4s.implicits.http4sLiteralsSyntax
import org.joda.time.DateTime
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.duration._

class BatchVideoServiceImplSpec extends AnyFlatSpec with Matchers {

  private val timestamp = new DateTime(2024, 5, 15, 10, 30)

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

  class StubVideoService(
    deleteByIdResult: Video = sampleVideo
  ) extends VideoService[IO, IO] {
    override def findVideoById(id: String, maybeUserId: Option[String]): IO[Video] =
      IO.pure(sampleVideo)

    override def deleteById(id: String, deleteVideoFile: Boolean): IO[Video] =
      IO.pure(deleteByIdResult)

    override val queueIncorrectlyCompletedVideos: IO[Seq[Video]] = IO.pure(Seq.empty)
  }

  class StubVideoDao(
    findByIdResult: Option[Video] = Some(sampleVideo),
    insertResult: Int = 1,
    findByVideoFileResourceIdResult: Option[Video] = None,
    incrementWatchTimeResult: Option[FiniteDuration] = Some(10.minutes)
  ) extends VideoDao[IO] {
    override def insert(
      videoMetadataId: String,
      videoFileResourceId: String,
      timestamp: DateTime,
      watchTime: FiniteDuration
    ): IO[Int] = IO.pure(insertResult)

    override def search(
      term: Option[String],
      videoUrls: Option[cats.data.NonEmptyList[org.http4s.Uri]],
      durationRange: com.ruchij.core.daos.scheduling.models.RangeValue[FiniteDuration],
      sizeRange: com.ruchij.core.daos.scheduling.models.RangeValue[Long],
      pageNumber: Int,
      pageSize: Int,
      sortBy: com.ruchij.core.services.models.SortBy,
      order: com.ruchij.core.services.models.Order,
      videoSites: Option[cats.data.NonEmptyList[VideoSite]],
      maybeUserId: Option[String]
    ): IO[Seq[Video]] = IO.pure(Seq.empty)

    override def incrementWatchTime(videoId: String, finiteDuration: FiniteDuration): IO[Option[FiniteDuration]] =
      IO.pure(incrementWatchTimeResult)

    override def findById(videoId: String, maybeUserId: Option[String]): IO[Option[Video]] =
      IO.pure(findByIdResult)

    override def findByVideoFileResourceId(fileResourceId: String): IO[Option[Video]] =
      IO.pure(findByVideoFileResourceIdResult)

    override def findByVideoPath(videoPath: String): IO[Option[Video]] =
      IO.pure(None)

    override def deleteById(videoId: String): IO[Int] = IO.pure(1)

    override def hasVideoFilePermission(videoFileResourceId: String, userId: String): IO[Boolean] = IO.pure(false)

    override val count: IO[Int] = IO.pure(0)
    override val duration: IO[FiniteDuration] = IO.pure(0.seconds)
    override val size: IO[Long] = IO.pure(0L)
    override val sites: IO[Set[VideoSite]] = IO.pure(Set.empty)
  }

  class StubVideoMetadataDao(
    updateResult: Int = 1
  ) extends VideoMetadataDao[IO] {
    override def insert(videoMetadata: VideoMetadata): IO[Int] = IO.pure(1)

    override def findById(id: String): IO[Option[VideoMetadata]] = IO.pure(Some(sampleVideoMetadata))

    override def findByUrl(uri: org.http4s.Uri): IO[Option[VideoMetadata]] = IO.pure(None)

    override def update(id: String, title: Option[String], size: Option[Long], duration: Option[FiniteDuration]): IO[Int] =
      IO.pure(updateResult)

    override def deleteById(videoMetadataId: String): IO[Int] = IO.pure(1)
  }

  class StubFileResourceDao(
    updateResult: Int = 1
  ) extends FileResourceDao[IO] {
    override def insert(resource: FileResource): IO[Int] = IO.pure(1)
    override def update(id: String, size: Long): IO[Int] = IO.pure(updateResult)
    override def getById(id: String): IO[Option[FileResource]] = IO.pure(Some(sampleFileResource))
    override def findByPath(path: String): IO[Option[FileResource]] = IO.pure(None)
    override def deleteById(id: String): IO[Int] = IO.pure(1)
  }

  private def createService(
    videoService: VideoService[IO, IO] = new StubVideoService(),
    videoDao: VideoDao[IO] = new StubVideoDao(),
    videoMetadataDao: VideoMetadataDao[IO] = new StubVideoMetadataDao(),
    fileResourceDao: FileResourceDao[IO] = new StubFileResourceDao()
  )(implicit jodaClock: JodaClock[IO]): BatchVideoServiceImpl[IO, IO] = {
    new BatchVideoServiceImpl[IO, IO](
      videoService,
      videoDao,
      videoMetadataDao,
      fileResourceDao
    )
  }

  "insert" should "create a new video entry" in runIO {
    implicit val jodaClock: JodaClock[IO] = Providers.stubClock[IO](timestamp)

    val videoDao = new StubVideoDao(findByIdResult = Some(sampleVideo))
    val service = createService(videoDao = videoDao)

    service.insert("video-1", "file-resource-1").map { video =>
      video.id mustBe "video-1"
      video.fileResource.id mustBe "file-resource-1"
    }
  }

  it should "throw InvalidConditionException when video cannot be retrieved after insert" in runIO {
    implicit val jodaClock: JodaClock[IO] = Providers.stubClock[IO](timestamp)

    val videoDao = new StubVideoDao(findByIdResult = None)
    val service = createService(videoDao = videoDao)

    service.insert("video-1", "file-resource-1").error.map { error =>
      error mustBe a[InvalidConditionException]
      error.getMessage must include("video-1")
    }
  }

  "fetchByVideoFileResourceId" should "return video when found" in runIO {
    implicit val jodaClock: JodaClock[IO] = Providers.stubClock[IO](timestamp)

    val videoDao = new StubVideoDao(findByVideoFileResourceIdResult = Some(sampleVideo))
    val service = createService(videoDao = videoDao)

    service.fetchByVideoFileResourceId("file-resource-1").map { video =>
      video mustBe sampleVideo
    }
  }

  it should "throw ResourceNotFoundException when video not found" in runIO {
    implicit val jodaClock: JodaClock[IO] = Providers.stubClock[IO](timestamp)

    val videoDao = new StubVideoDao(findByVideoFileResourceIdResult = None)
    val service = createService(videoDao = videoDao)

    service.fetchByVideoFileResourceId("non-existent").error.map { error =>
      error mustBe a[ResourceNotFoundException]
      error.getMessage must include("non-existent")
    }
  }

  "incrementWatchTime" should "return updated watch time" in runIO {
    implicit val jodaClock: JodaClock[IO] = Providers.stubClock[IO](timestamp)

    val videoDao = new StubVideoDao(incrementWatchTimeResult = Some(15.minutes))
    val service = createService(videoDao = videoDao)

    service.incrementWatchTime("video-1", 5.minutes).map { watchTime =>
      watchTime mustBe 15.minutes
    }
  }

  it should "throw ResourceNotFoundException when video not found" in runIO {
    implicit val jodaClock: JodaClock[IO] = Providers.stubClock[IO](timestamp)

    val videoDao = new StubVideoDao(incrementWatchTimeResult = None)
    val service = createService(videoDao = videoDao)

    service.incrementWatchTime("non-existent", 5.minutes).error.map { error =>
      error mustBe a[ResourceNotFoundException]
      error.getMessage must include("non-existent")
    }
  }

  "update" should "update video metadata and file resource size" in runIO {
    implicit val jodaClock: JodaClock[IO] = Providers.stubClock[IO](timestamp)

    val updatedFileResource = sampleFileResource.copy(size = 200 * 1024 * 1024L)
    val updatedVideoMetadata = sampleVideoMetadata.copy(size = 200 * 1024 * 1024L)
    val updatedVideo = sampleVideo.copy(videoMetadata = updatedVideoMetadata, fileResource = updatedFileResource)

    var findByIdCalls = 0
    val videoDao = new StubVideoDao() {
      override def findById(videoId: String, maybeUserId: Option[String]): IO[Option[Video]] = {
        findByIdCalls += 1
        if (findByIdCalls == 1) IO.pure(Some(sampleVideo))
        else IO.pure(Some(updatedVideo))
      }
    }

    val service = createService(videoDao = videoDao)

    service.update("video-1", 200 * 1024 * 1024L).map { video =>
      video.fileResource.size mustBe 200 * 1024 * 1024L
    }
  }

  it should "throw ResourceNotFoundException when video not found" in runIO {
    implicit val jodaClock: JodaClock[IO] = Providers.stubClock[IO](timestamp)

    val videoDao = new StubVideoDao(findByIdResult = None)
    val service = createService(videoDao = videoDao)

    service.update("non-existent", 200 * 1024 * 1024L).error.map { error =>
      error mustBe a[ResourceNotFoundException]
      error.getMessage must include("non-existent")
    }
  }

  "deleteById" should "delegate to videoService.deleteById" in runIO {
    implicit val jodaClock: JodaClock[IO] = Providers.stubClock[IO](timestamp)

    var deleteByIdCalled = false
    var deleteVideoFileParam = false

    val videoService = new StubVideoService() {
      override def deleteById(id: String, deleteVideoFile: Boolean): IO[Video] = {
        deleteByIdCalled = true
        deleteVideoFileParam = deleteVideoFile
        IO.pure(sampleVideo)
      }
    }

    val service = createService(videoService = videoService)

    service.deleteById("video-1", deleteVideoFile = true).map { video =>
      video mustBe sampleVideo
      deleteByIdCalled mustBe true
      deleteVideoFileParam mustBe true
    }
  }

  it should "pass deleteVideoFile=false to videoService" in runIO {
    implicit val jodaClock: JodaClock[IO] = Providers.stubClock[IO](timestamp)

    var deleteVideoFileParam = true

    val videoService = new StubVideoService() {
      override def deleteById(id: String, deleteVideoFile: Boolean): IO[Video] = {
        deleteVideoFileParam = deleteVideoFile
        IO.pure(sampleVideo)
      }
    }

    val service = createService(videoService = videoService)

    service.deleteById("video-1", deleteVideoFile = false).map { video =>
      video mustBe sampleVideo
      deleteVideoFileParam mustBe false
    }
  }
}
