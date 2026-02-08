package com.ruchij.api.services.asset

import cats.effect.IO
import cats.~>
import com.ruchij.api.daos.playlist.PlaylistDao
import com.ruchij.api.daos.playlist.models.{Playlist, PlaylistSortBy}
import com.ruchij.api.daos.user.models.{Email, Role, User}
import com.ruchij.api.services.asset.AssetService.FileByteRange
import com.ruchij.core.daos.resource.FileResourceDao
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.snapshot.SnapshotDao
import com.ruchij.core.daos.snapshot.models.Snapshot
import com.ruchij.core.daos.video.VideoDao
import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.daos.videometadata.VideoMetadataDao
import com.ruchij.core.daos.videometadata.models.{VideoMetadata, VideoSite}
import com.ruchij.core.exceptions.ResourceNotFoundException
import com.ruchij.core.messaging.Publisher
import com.ruchij.core.messaging.models.VideoWatchMetric
import com.ruchij.core.services.models.Order
import com.ruchij.core.services.repository.RepositoryService
import com.ruchij.core.test.IOSupport.{IOWrapper, runIO}
import com.ruchij.core.test.Providers
import com.ruchij.core.types.{Clock, TimeUtils}
import fs2.Stream
import org.http4s.{MediaType, Uri}
import org.http4s.implicits.http4sLiteralsSyntax
import java.time.Instant
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.duration._

class AssetServiceImplSpec extends AnyFlatSpec with Matchers {

  private val timestamp = TimeUtils.instantOf(2024, 5, 15, 10, 30)

  implicit val transaction: IO ~> IO = new (IO ~> IO) {
    override def apply[A](fa: IO[A]): IO[A] = fa
  }

  private val sampleFileResource =
    FileResource("file-resource-1", timestamp, "/videos/video1.mp4", MediaType.video.mp4, 1024 * 1024L)

  private val sampleThumbnail =
    FileResource("thumbnail-1", timestamp, "/thumbnails/thumb1.jpg", MediaType.image.jpeg, 50000L)

  private val snapshotFileResource =
    FileResource("snapshot-file-1", timestamp, "/snapshots/snap1.jpg", MediaType.image.jpeg, 30000L)

  private val sampleVideoMetadata = VideoMetadata(
    uri"https://example.com/video1",
    "video-1",
    VideoSite.YTDownloaderSite("youtube"),
    "Sample Video",
    10.minutes,
    1024 * 1024L,
    sampleThumbnail
  )

  private val sampleVideo = Video(sampleVideoMetadata, sampleFileResource, timestamp, 5.minutes)

  private val adminUser = User("admin-1", timestamp, "Admin", "User", Email("admin@example.com"), Role.Admin)
  private val normalUser = User("user-1", timestamp, "Normal", "User", Email("user@example.com"), Role.User)

  class StubFileResourceDao(
    getByIdResult: String => Option[FileResource] = _ => Some(sampleFileResource)
  ) extends FileResourceDao[IO] {
    override def insert(resource: FileResource): IO[Int] = IO.pure(1)
    override def update(id: String, size: Long): IO[Int] = IO.pure(1)
    override def getById(id: String): IO[Option[FileResource]] = IO.pure(getByIdResult(id))
    override def findByPath(path: String): IO[Option[FileResource]] = IO.pure(None)
    override def deleteById(id: String): IO[Int] = IO.pure(1)
  }

  class StubSnapshotDao(
    hasPermissionResult: Boolean = true,
    isSnapshotFileResourceResult: Boolean = true
  ) extends SnapshotDao[IO] {
    override def insert(snapshot: Snapshot): IO[Int] = IO.pure(1)
    override def findByVideo(videoId: String, maybeUserId: Option[String]): IO[Seq[Snapshot]] = IO.pure(Seq.empty)
    override def hasPermission(snapshotFileResourceId: String, userId: String): IO[Boolean] = IO.pure(hasPermissionResult)
    override def isSnapshotFileResource(fileResourceId: String): IO[Boolean] = IO.pure(isSnapshotFileResourceResult)
    override def deleteByVideo(videoId: String): IO[Int] = IO.pure(1)
  }

  class StubVideoDao(
    hasVideoFilePermissionResult: Boolean = true,
    isVideoFileResourceExistResult: Boolean = true
  ) extends VideoDao[IO] {
    override def insert(
      videoMetadataId: String,
      videoFileResourceId: String,
      timestamp: Instant,
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
      IO.pure(Some(finiteDuration))

    override def findById(videoId: String, maybeUserId: Option[String]): IO[Option[Video]] = IO.pure(Some(sampleVideo))

    override def findByVideoFileResourceId(fileResourceId: String): IO[Option[Video]] = IO.pure(None)

    override def findByVideoPath(videoPath: String): IO[Option[Video]] = IO.pure(None)

    override def deleteById(videoId: String): IO[Int] = IO.pure(1)

    override def hasVideoFilePermission(videoFileResourceId: String, userId: String): IO[Boolean] =
      IO.pure(hasVideoFilePermissionResult)

    override def isVideoFileResourceExist(videoFileResourceId: String): IO[Boolean] =
      IO.pure(isVideoFileResourceExistResult)

    override val count: IO[Int] = IO.pure(0)
    override val duration: IO[FiniteDuration] = IO.pure(0.seconds)
    override val size: IO[Long] = IO.pure(0L)
    override val sites: IO[Set[VideoSite]] = IO.pure(Set.empty)
  }

  class StubRepositoryService(
    readResult: Option[Stream[IO, Byte]] = Some(Stream.emits("test-content".getBytes))
  ) extends RepositoryService[IO] {
    override type BackedType = String
    override def write(key: Key, data: Stream[IO, Byte]): Stream[IO, Nothing] = Stream.empty
    override def read(key: Key, start: Option[Long], end: Option[Long]): IO[Option[Stream[IO, Byte]]] =
      IO.pure(readResult)
    override def size(key: Key): IO[Option[Long]] = IO.pure(Some(1024L))
    override def list(key: Key): Stream[IO, Key] = Stream.empty
    override def exists(key: Key): IO[Boolean] = IO.pure(true)
    override def backedType(key: Key): IO[BackedType] = IO.pure("file")
    override def delete(key: Key): IO[Boolean] = IO.pure(true)
    override def fileType(key: Key): IO[Option[MediaType]] = IO.pure(Some(MediaType.video.mp4))
  }

  class StubPublisher extends Publisher[IO, VideoWatchMetric] {
    var publishedMetrics: List[VideoWatchMetric] = List.empty
    override def publishOne(value: VideoWatchMetric): IO[Unit] = IO.delay {
      publishedMetrics = publishedMetrics :+ value
    }
    override val publish: fs2.Pipe[IO, VideoWatchMetric, Unit] = _.evalMap(publishOne)
  }

  class StubVideoMetadataDao(
    isThumbnailFileResourceResult: Boolean = true
  ) extends VideoMetadataDao[IO] {
    override def insert(videoMetadata: VideoMetadata): IO[Int] = IO.pure(1)
    override def update(videoMetadataId: String, title: Option[String], size: Option[Long], maybeDuration: Option[FiniteDuration]): IO[Int] = IO.pure(1)
    override def findById(videoMetadataId: String): IO[Option[VideoMetadata]] = IO.pure(None)
    override def isThumbnailFileResource(thumbnailId: String): IO[Boolean] = IO.pure(isThumbnailFileResourceResult)
    override def findByUrl(uri: Uri): IO[Option[VideoMetadata]] = IO.pure(None)
    override def deleteById(videoMetadataId: String): IO[Int] = IO.pure(1)
  }

  class StubPlaylistDao(
    isAlbumArtFileResourceResult: Boolean = true,
    hasAlbumArtPermissionResult: Boolean = true
  ) extends PlaylistDao[IO] {
    override def insert(playlist: Playlist): IO[Int] = IO.pure(1)
    override def update(playlistId: String, maybeTitle: Option[String], maybeDescription: Option[String], maybeVideoIds: Option[Seq[String]], maybeAlbumArt: Option[Either[Unit, String]], maybeUserId: Option[String]): IO[Int] = IO.pure(1)
    override def findById(playlistId: String, maybeUserId: Option[String]): IO[Option[Playlist]] = IO.pure(None)
    override def search(maybeSearchTerm: Option[String], pageSize: Int, pageNumber: Int, order: Order, sortBy: PlaylistSortBy, maybeUserId: Option[String]): IO[Seq[Playlist]] = IO.pure(Seq.empty)
    override def isAlbumArtFileResource(fileResourceId: String): IO[Boolean] = IO.pure(isAlbumArtFileResourceResult)
    override def hasAlbumArtPermission(fileResourceId: String, userId: String): IO[Boolean] = IO.pure(hasAlbumArtPermissionResult)
    override def deleteById(playlistId: String, maybeUserId: Option[String]): IO[Int] = IO.pure(1)
  }

  private def createService(
    fileResourceDao: FileResourceDao[IO] = new StubFileResourceDao(),
    snapshotDao: SnapshotDao[IO] = new StubSnapshotDao(),
    videoDao: VideoDao[IO] = new StubVideoDao(),
    videoMetadataDao: VideoMetadataDao[IO] = new StubVideoMetadataDao(),
    playlistDao: PlaylistDao[IO] = new StubPlaylistDao(),
    repositoryService: RepositoryService[IO] = new StubRepositoryService(),
    publisher: Publisher[IO, VideoWatchMetric] = new StubPublisher()
  )(implicit clock: Clock[IO]): AssetServiceImpl[IO, IO] = {
    new AssetServiceImpl[IO, IO](
      fileResourceDao,
      snapshotDao,
      videoDao,
      videoMetadataDao,
      playlistDao,
      repositoryService,
      publisher
    )
  }

  "thumbnail" should "return asset for a valid thumbnail" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val fileResourceDao = new StubFileResourceDao(getByIdResult = id =>
      if (id == "thumbnail-1") Some(sampleThumbnail) else None
    )
    val service = createService(fileResourceDao = fileResourceDao)

    service.thumbnail("thumbnail-1").map { asset =>
      asset.fileResource.id mustBe "thumbnail-1"
      asset.fileRange.start mustBe 0L
    }
  }

  it should "throw ResourceNotFoundException when thumbnail not found" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val fileResourceDao = new StubFileResourceDao(getByIdResult = _ => None)
    val service = createService(fileResourceDao = fileResourceDao)

    service.thumbnail("non-existent").error.map { error =>
      error mustBe a[ResourceNotFoundException]
    }
  }

  "snapshot" should "return asset for admin user" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val fileResourceDao = new StubFileResourceDao(getByIdResult = id =>
      if (id == "snapshot-file-1") Some(snapshotFileResource) else None
    )
    val service = createService(fileResourceDao = fileResourceDao)

    service.snapshot("snapshot-file-1", adminUser).map { asset =>
      asset.fileResource.id mustBe "snapshot-file-1"
    }
  }

  it should "return asset for normal user with permission" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val fileResourceDao = new StubFileResourceDao(getByIdResult = id =>
      if (id == "snapshot-file-1") Some(snapshotFileResource) else None
    )
    val snapshotDao = new StubSnapshotDao(hasPermissionResult = true)
    val service = createService(fileResourceDao = fileResourceDao, snapshotDao = snapshotDao)

    service.snapshot("snapshot-file-1", normalUser).map { asset =>
      asset.fileResource.id mustBe "snapshot-file-1"
    }
  }

  it should "throw ResourceNotFoundException for normal user without permission" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val snapshotDao = new StubSnapshotDao(hasPermissionResult = false)
    val service = createService(snapshotDao = snapshotDao)

    service.snapshot("snapshot-file-1", normalUser).error.map { error =>
      error mustBe a[ResourceNotFoundException]
    }
  }

  "videoFile" should "return asset for admin user" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val publisher = new StubPublisher()
    val service = createService(publisher = publisher)

    service.videoFile("file-resource-1", adminUser, None, None).map { asset =>
      asset.fileResource.id mustBe "file-resource-1"
      publisher.publishedMetrics.size mustBe 1
      publisher.publishedMetrics.head.userId mustBe adminUser.id
    }
  }

  it should "return asset for normal user with permission" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val videoDao = new StubVideoDao(hasVideoFilePermissionResult = true)
    val publisher = new StubPublisher()
    val service = createService(videoDao = videoDao, publisher = publisher)

    service.videoFile("file-resource-1", normalUser, None, None).map { asset =>
      asset.fileResource.id mustBe "file-resource-1"
      publisher.publishedMetrics.size mustBe 1
    }
  }

  it should "throw ResourceNotFoundException for normal user without permission" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val videoDao = new StubVideoDao(hasVideoFilePermissionResult = false)
    val service = createService(videoDao = videoDao)

    service.videoFile("file-resource-1", normalUser, None, None).error.map { error =>
      error mustBe a[ResourceNotFoundException]
    }
  }

  it should "handle byte range requests" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val fileResourceDao = new StubFileResourceDao(getByIdResult = _ =>
      Some(sampleFileResource.copy(size = 10000L))
    )
    val publisher = new StubPublisher()
    val service = createService(fileResourceDao = fileResourceDao, publisher = publisher)

    val byteRange = FileByteRange(100L, Some(500L))
    service.videoFile("file-resource-1", adminUser, Some(byteRange), None).map { asset =>
      asset.fileRange.start mustBe 100L
      asset.fileRange.end mustBe 500L
    }
  }

  it should "handle max stream size" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val fileResourceDao = new StubFileResourceDao(getByIdResult = _ =>
      Some(sampleFileResource.copy(size = 10000L))
    )
    val publisher = new StubPublisher()
    val service = createService(fileResourceDao = fileResourceDao, publisher = publisher)

    service.videoFile("file-resource-1", adminUser, None, Some(1000L)).map { asset =>
      asset.fileRange.start mustBe 0L
      asset.fileRange.end mustBe 1000L
    }
  }

  it should "throw ResourceNotFoundException when file resource not found" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val fileResourceDao = new StubFileResourceDao(getByIdResult = _ => None)
    val service = createService(fileResourceDao = fileResourceDao)

    service.videoFile("non-existent", adminUser, None, None).error.map { error =>
      error mustBe a[ResourceNotFoundException]
    }
  }

  it should "throw ResourceNotFoundException when repository read returns None" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val repositoryService = new StubRepositoryService(readResult = None)
    val service = createService(repositoryService = repositoryService)

    service.videoFile("file-resource-1", adminUser, None, None).error.map { error =>
      error mustBe a[ResourceNotFoundException]
    }
  }

  "albumArt" should "return asset for admin user" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val albumArtFileResource = FileResource("album-art-1", timestamp, "/album-art/art1.jpg", MediaType.image.jpeg, 40000L)
    val fileResourceDao = new StubFileResourceDao(getByIdResult = id =>
      if (id == "album-art-1") Some(albumArtFileResource) else None
    )
    val service = createService(fileResourceDao = fileResourceDao)

    service.albumArt("album-art-1", adminUser).map { asset =>
      asset.fileResource.id mustBe "album-art-1"
    }
  }

  it should "return asset for normal user with permission" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val albumArtFileResource = FileResource("album-art-1", timestamp, "/album-art/art1.jpg", MediaType.image.jpeg, 40000L)
    val fileResourceDao = new StubFileResourceDao(getByIdResult = id =>
      if (id == "album-art-1") Some(albumArtFileResource) else None
    )
    val playlistDao = new StubPlaylistDao(hasAlbumArtPermissionResult = true)
    val service = createService(fileResourceDao = fileResourceDao, playlistDao = playlistDao)

    service.albumArt("album-art-1", normalUser).map { asset =>
      asset.fileResource.id mustBe "album-art-1"
    }
  }

  it should "throw ResourceNotFoundException for normal user without permission" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val playlistDao = new StubPlaylistDao(hasAlbumArtPermissionResult = false)
    val service = createService(playlistDao = playlistDao)

    service.albumArt("album-art-1", normalUser).error.map { error =>
      error mustBe a[ResourceNotFoundException]
    }
  }
}
