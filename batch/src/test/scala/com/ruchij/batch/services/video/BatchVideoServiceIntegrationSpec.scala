package com.ruchij.batch.services.video

import cats.effect.IO
import cats.implicits._
import cats.~>
import com.ruchij.batch.external.BatchResourcesProvider
import com.ruchij.batch.external.containers.ContainerBatchResourcesProvider
import com.ruchij.core.daos.resource.DoobieFileResourceDao
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.video.DoobieVideoDao
import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.daos.videometadata.DoobieVideoMetadataDao
import com.ruchij.core.daos.videometadata.models.{VideoMetadata, VideoSite}
import com.ruchij.core.exceptions.ResourceNotFoundException
import com.ruchij.core.services.video.VideoService
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.test.Providers
import com.ruchij.core.test.data.DataGenerators
import com.ruchij.core.types.JodaClock
import doobie.free.connection.ConnectionIO
import org.http4s.MediaType
import org.http4s.implicits.http4sLiteralsSyntax
import org.joda.time.DateTime
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class BatchVideoServiceIntegrationSpec extends AnyFlatSpec with Matchers with OptionValues {

  val timestamp = new DateTime(2024, 5, 15, 10, 30)

  def insertVideoMetadata(implicit transactor: ConnectionIO ~> IO): IO[VideoMetadata] =
    for {
      videoMetadata <- DataGenerators.videoMetadata[IO].generate
      _ <- transactor {
        DoobieFileResourceDao.insert(videoMetadata.thumbnail)
          .productR(DoobieVideoMetadataDao.insert(videoMetadata))
      }
    } yield videoMetadata

  def insertFileResource(implicit transactor: ConnectionIO ~> IO): IO[FileResource] =
    for {
      fileResource <- DataGenerators.fileResource[IO](cats.data.NonEmptyList.of(MediaType.video.mp4)).generate
      _ <- transactor(DoobieFileResourceDao.insert(fileResource))
    } yield fileResource

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

  // Stub VideoService that doesn't require all the DAOs
  class StubVideoService extends VideoService[IO, IO] {
    override def findVideoById(id: String, maybeUserId: Option[String]): IO[Video] =
      IO.pure(sampleVideo)

    override def deleteById(id: String, deleteVideoFile: Boolean): IO[Video] =
      IO.pure(sampleVideo)

    override val queueIncorrectlyCompletedVideos: IO[Seq[Video]] = IO.pure(Seq.empty)
  }

  implicit val transaction: IO ~> IO = new (IO ~> IO) {
    override def apply[A](fa: IO[A]): IO[A] = fa
  }

  def createBatchVideoService(
    videoService: VideoService[IO, IO] = new StubVideoService
  )(implicit jodaClock: JodaClock[IO]): BatchVideoServiceImpl[IO, IO] = {
    new BatchVideoServiceImpl[IO, IO](
      videoService,
      new StubVideoDao,
      new StubVideoMetadataDao,
      new StubFileResourceDao
    )
  }

  class StubVideoDao(
    findByIdResult: Option[Video] = Some(sampleVideo),
    findByVideoFileResourceIdResult: Option[Video] = None,
    incrementWatchTimeResult: Option[FiniteDuration] = Some(10.minutes)
  ) extends com.ruchij.core.daos.video.VideoDao[IO] {
    override def insert(
      videoMetadataId: String,
      videoFileResourceId: String,
      timestamp: DateTime,
      watchTime: FiniteDuration
    ): IO[Int] = IO.pure(1)

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

  class StubVideoMetadataDao extends com.ruchij.core.daos.videometadata.VideoMetadataDao[IO] {
    override def insert(videoMetadata: VideoMetadata): IO[Int] = IO.pure(1)
    override def findById(id: String): IO[Option[VideoMetadata]] = IO.pure(Some(sampleVideoMetadata))
    override def findByUrl(uri: org.http4s.Uri): IO[Option[VideoMetadata]] = IO.pure(None)
    override def update(id: String, title: Option[String], size: Option[Long], duration: Option[FiniteDuration]): IO[Int] = IO.pure(1)
    override def deleteById(videoMetadataId: String): IO[Int] = IO.pure(1)
  }

  class StubFileResourceDao extends com.ruchij.core.daos.resource.FileResourceDao[IO] {
    override def insert(resource: FileResource): IO[Int] = IO.pure(1)
    override def update(id: String, size: Long): IO[Int] = IO.pure(1)
    override def getById(id: String): IO[Option[FileResource]] = IO.pure(Some(sampleFileResource))
    override def findByPath(path: String): IO[Option[FileResource]] = IO.pure(None)
    override def deleteById(id: String): IO[Int] = IO.pure(1)
  }

  "insert" should "create a new video entry" in runIO {
    implicit val jodaClock: JodaClock[IO] = Providers.stubClock[IO](timestamp)

    val service = createBatchVideoService()

    service.insert("video-1", "file-resource-1").map { video =>
      video.id mustBe "video-1"
    }
  }

  "incrementWatchTime" should "return updated watch time" in runIO {
    implicit val jodaClock: JodaClock[IO] = Providers.stubClock[IO](timestamp)

    val service = createBatchVideoService()

    service.incrementWatchTime("video-1", 5.minutes).map { watchTime =>
      watchTime mustBe 10.minutes
    }
  }

  it should "fail for non-existent video" in runIO {
    implicit val jodaClock: JodaClock[IO] = Providers.stubClock[IO](timestamp)

    val videoDao = new StubVideoDao(incrementWatchTimeResult = None)
    val service = new BatchVideoServiceImpl[IO, IO](
      new StubVideoService,
      videoDao,
      new StubVideoMetadataDao,
      new StubFileResourceDao
    )

    service.incrementWatchTime("non-existent", 5.minutes).attempt.map { result =>
      result.isLeft mustBe true
      result.left.toOption.value mustBe a[ResourceNotFoundException]
    }
  }

  "update" should "update video size" in runIO {
    implicit val jodaClock: JodaClock[IO] = Providers.stubClock[IO](timestamp)

    val service = createBatchVideoService()

    service.update("video-1", 200 * 1024 * 1024L).map { video =>
      video.videoMetadata.id mustBe "video-1"
    }
  }

  it should "fail for non-existent video" in runIO {
    implicit val jodaClock: JodaClock[IO] = Providers.stubClock[IO](timestamp)

    val videoDao = new StubVideoDao(findByIdResult = None)
    val service = new BatchVideoServiceImpl[IO, IO](
      new StubVideoService,
      videoDao,
      new StubVideoMetadataDao,
      new StubFileResourceDao
    )

    service.update("non-existent", 200 * 1024 * 1024L).attempt.map { result =>
      result.isLeft mustBe true
      result.left.toOption.value mustBe a[ResourceNotFoundException]
    }
  }

  "fetchByVideoFileResourceId" should "return video when found" in runIO {
    implicit val jodaClock: JodaClock[IO] = Providers.stubClock[IO](timestamp)

    val videoDao = new StubVideoDao(findByVideoFileResourceIdResult = Some(sampleVideo))
    val service = new BatchVideoServiceImpl[IO, IO](
      new StubVideoService,
      videoDao,
      new StubVideoMetadataDao,
      new StubFileResourceDao
    )

    service.fetchByVideoFileResourceId("file-resource-1").map { video =>
      video mustBe sampleVideo
    }
  }

  it should "fail for non-existent file resource ID" in runIO {
    implicit val jodaClock: JodaClock[IO] = Providers.stubClock[IO](timestamp)

    val service = createBatchVideoService()

    service.fetchByVideoFileResourceId("non-existent").attempt.map { result =>
      result.isLeft mustBe true
      result.left.toOption.value mustBe a[ResourceNotFoundException]
    }
  }

  "deleteById" should "delegate to videoService" in runIO {
    implicit val jodaClock: JodaClock[IO] = Providers.stubClock[IO](timestamp)

    var deleteVideoFileCalled = false

    val videoService = new StubVideoService {
      override def deleteById(id: String, deleteVideoFile: Boolean): IO[Video] = {
        deleteVideoFileCalled = deleteVideoFile
        IO.pure(sampleVideo)
      }
    }

    val service = createBatchVideoService(videoService)

    service.deleteById("video-1", deleteVideoFile = true).map { video =>
      video mustBe sampleVideo
      deleteVideoFileCalled mustBe true
    }
  }

  "database integration" should "insert and retrieve video data" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]

    batchServiceProvider.transactor.use { implicit transactor =>
      for {
        // Insert video metadata
        videoMetadata <- insertVideoMetadata

        // Insert a video file resource
        fileResource <- DataGenerators.fileResource[IO](cats.data.NonEmptyList.of(MediaType.video.mp4)).generate
        _ <- transactor(DoobieFileResourceDao.insert(fileResource))

        // Insert video record
        timestamp <- IO.realTimeInstant.map(instant => new DateTime(instant.toEpochMilli))
        _ <- transactor(DoobieVideoDao.insert(videoMetadata.id, fileResource.id, timestamp, 0.seconds))

        // Verify video can be retrieved
        video <- transactor(DoobieVideoDao.findById(videoMetadata.id, None))

        _ <- IO.delay {
          video mustBe defined
          video.get.videoMetadata.id mustBe videoMetadata.id
          video.get.fileResource.id mustBe fileResource.id
        }
      } yield ()
    }
  }

  it should "handle concurrent operations" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]
    val concurrency = 10

    batchServiceProvider.transactor.use { implicit transactor =>
      fs2.Stream
        .range(0, concurrency)
        .covary[IO]
        .parEvalMapUnordered(concurrency) { _ =>
          for {
            videoMetadata <- insertVideoMetadata
            fileResource <- DataGenerators.fileResource[IO](cats.data.NonEmptyList.of(MediaType.video.mp4)).generate
            _ <- transactor(DoobieFileResourceDao.insert(fileResource))
            timestamp <- IO.realTimeInstant.map(instant => new DateTime(instant.toEpochMilli))
            _ <- transactor(DoobieVideoDao.insert(videoMetadata.id, fileResource.id, timestamp, 0.seconds))
            video <- transactor(DoobieVideoDao.findById(videoMetadata.id, None))
          } yield video
        }
        .compile
        .toList
        .flatMap { videos =>
          IO.delay {
            videos.size mustBe concurrency
            all(videos.map(_.isDefined)) mustBe true
          }
        }
    }
  }
}
