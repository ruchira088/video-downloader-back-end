package com.ruchij.api.services.playlist

import cats.effect.IO
import cats.~>
import com.ruchij.api.daos.playlist.PlaylistDao
import com.ruchij.api.daos.playlist.models.{Playlist, PlaylistSortBy}
import com.ruchij.core.config.StorageConfiguration
import com.ruchij.core.daos.resource.FileResourceDao
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.daos.videometadata.models.{VideoMetadata, VideoSite}
import com.ruchij.core.exceptions.{InvalidConditionException, ResourceNotFoundException}
import com.ruchij.core.services.models.Order
import com.ruchij.core.services.repository.RepositoryService
import com.ruchij.core.test.IOSupport.{IOWrapper, runIO}
import com.ruchij.core.test.Providers
import com.ruchij.core.types.{Clock, RandomGenerator, TimeUtils}
import fs2.Stream
import org.http4s.MediaType
import org.http4s.implicits._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.util.UUID
import scala.collection.mutable
import scala.concurrent.duration._

class PlaylistServiceImplSpec extends AnyFlatSpec with Matchers {

  private val timestamp = TimeUtils.instantOf(2024, 5, 15, 10, 30)
  private val testUuid = UUID.fromString("12345678-1234-1234-1234-123456789abc")

  implicit val transaction: IO ~> IO = new (IO ~> IO) {
    override def apply[A](fa: IO[A]): IO[A] = fa
  }

  private val storageConfiguration = StorageConfiguration("videos", "images", List.empty)

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

  private val sampleVideoFileResource = FileResource(
    "file-resource-1",
    timestamp,
    "/videos/video1.mp4",
    MediaType.video.mp4,
    1024 * 1024 * 100L
  )

  private val sampleVideo = Video(
    sampleVideoMetadata,
    sampleVideoFileResource,
    timestamp,
    5.minutes
  )

  private val sampleAlbumArt = FileResource(
    "album-art-1",
    timestamp,
    "/images/album-art.jpg",
    MediaType.image.jpeg,
    50000L
  )

  private val samplePlaylist = Playlist(
    "playlist-1",
    "user-1",
    timestamp,
    "My Playlist",
    Some("A test playlist"),
    Seq(sampleVideo),
    None
  )

  def stubUuidGenerator(uuid: UUID): RandomGenerator[IO, UUID] =
    new RandomGenerator[IO, UUID] {
      override def generate: IO[UUID] = IO.pure(uuid)
    }

  class StubPlaylistDao(
    findByIdResult: (String, Option[String]) => Option[Playlist] = (_, _) => None,
    searchResult: Seq[Playlist] = Seq.empty,
    insertResult: Int = 1,
    updateResult: Int = 1,
    deleteResult: Int = 1
  ) extends PlaylistDao[IO] {
    override def insert(playlist: Playlist): IO[Int] = IO.pure(insertResult)

    override def update(
      id: String,
      maybeTitle: Option[String],
      maybeDescription: Option[String],
      maybeVideoIds: Option[Seq[String]],
      maybeAlbumArtId: Option[Either[Unit, String]],
      maybeUserId: Option[String]
    ): IO[Int] = IO.pure(updateResult)

    override def findById(id: String, maybeUserId: Option[String]): IO[Option[Playlist]] =
      IO.pure(findByIdResult(id, maybeUserId))

    override def search(
      maybeSearchTerm: Option[String],
      pageSize: Int,
      pageNumber: Int,
      order: Order,
      playlistSortBy: PlaylistSortBy,
      maybeUserId: Option[String]
    ): IO[Seq[Playlist]] = IO.pure(searchResult)

    override def isAlbumArtFileResource(fileResourceId: String): IO[Boolean] = IO.pure(false)

    override def hasAlbumArtPermission(fileResourceId: String, userId: String): IO[Boolean] = IO.pure(false)

    override def deleteById(id: String, maybeUserId: Option[String]): IO[Int] = IO.pure(deleteResult)
  }

  class StubFileResourceDao(
    insertResult: Int = 1
  ) extends FileResourceDao[IO] {
    override def insert(resource: FileResource): IO[Int] = IO.pure(insertResult)
    override def update(id: String, size: Long): IO[Int] = IO.pure(1)
    override def getById(id: String): IO[Option[FileResource]] = IO.pure(None)
    override def findByPath(path: String): IO[Option[FileResource]] = IO.pure(None)
    override def deleteById(id: String): IO[Int] = IO.pure(1)
  }

  class StubRepositoryService(
    writeResult: Boolean = true,
    sizeResult: Option[Long] = Some(50000L)
  ) extends RepositoryService[IO] {
    override type BackedType = String
    val writtenKeys: mutable.ListBuffer[String] = mutable.ListBuffer.empty

    override def write(key: Key, data: Stream[IO, Byte]): Stream[IO, Nothing] =
      Stream.exec(IO.delay(writtenKeys += key).void) ++ Stream.empty

    override def read(key: Key, start: Option[Long], end: Option[Long]): IO[Option[Stream[IO, Byte]]] =
      IO.pure(None)

    override def size(key: Key): IO[Option[Long]] = IO.pure(sizeResult)

    override def list(key: Key): Stream[IO, Key] = Stream.empty

    override def exists(key: Key): IO[Boolean] = IO.pure(false)

    override def backedType(key: Key): IO[BackedType] = IO.pure("file")

    override def delete(key: Key): IO[Boolean] = IO.pure(true)

    override def fileType(key: Key): IO[Option[MediaType]] = IO.pure(None)
  }

  private def createService(
    playlistDao: PlaylistDao[IO] = new StubPlaylistDao(),
    fileResourceDao: FileResourceDao[IO] = new StubFileResourceDao(),
    repositoryService: RepositoryService[IO] = new StubRepositoryService()
  )(implicit clock: Clock[IO], randomGenerator: RandomGenerator[IO, UUID]): PlaylistServiceImpl[IO, IO] = {
    new PlaylistServiceImpl[IO, IO](
      playlistDao,
      fileResourceDao,
      repositoryService,
      storageConfiguration
    )
  }

  // create tests
  "create" should "create a new playlist" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    implicit val randomGenerator: RandomGenerator[IO, UUID] = stubUuidGenerator(testUuid)

    val playlistDao = new StubPlaylistDao()
    val service = createService(playlistDao = playlistDao)

    service.create("My New Playlist", Some("Description"), "user-1").map { playlist =>
      playlist.id mustBe testUuid.toString
      playlist.userId mustBe "user-1"
      playlist.title mustBe "My New Playlist"
      playlist.description mustBe Some("Description")
      playlist.createdAt mustBe timestamp
      playlist.videos mustBe empty
      playlist.albumArt mustBe None
    }
  }

  it should "create playlist without description" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    implicit val randomGenerator: RandomGenerator[IO, UUID] = stubUuidGenerator(testUuid)

    val service = createService()

    service.create("Simple Playlist", None, "user-2").map { playlist =>
      playlist.title mustBe "Simple Playlist"
      playlist.description mustBe None
    }
  }

  // updatePlaylist tests
  "updatePlaylist" should "update playlist title" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    implicit val randomGenerator: RandomGenerator[IO, UUID] = stubUuidGenerator(testUuid)

    val updatedPlaylist = samplePlaylist.copy(title = "Updated Title")
    val playlistDao = new StubPlaylistDao(
      findByIdResult = (id, _) => if (id == "playlist-1") Some(updatedPlaylist) else None,
      updateResult = 1
    )

    val service = createService(playlistDao = playlistDao)

    service.updatePlaylist("playlist-1", Some("Updated Title"), None, None, None).map { result =>
      result.title mustBe "Updated Title"
    }
  }

  it should "update playlist description" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    implicit val randomGenerator: RandomGenerator[IO, UUID] = stubUuidGenerator(testUuid)

    val updatedPlaylist = samplePlaylist.copy(description = Some("New Description"))
    val playlistDao = new StubPlaylistDao(
      findByIdResult = (id, _) => if (id == "playlist-1") Some(updatedPlaylist) else None
    )

    val service = createService(playlistDao = playlistDao)

    service.updatePlaylist("playlist-1", None, Some("New Description"), None, None).map { result =>
      result.description mustBe Some("New Description")
    }
  }

  it should "update playlist video IDs" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    implicit val randomGenerator: RandomGenerator[IO, UUID] = stubUuidGenerator(testUuid)

    val video2 = sampleVideo.copy(videoMetadata = sampleVideoMetadata.copy(id = "video-2"))
    val updatedPlaylist = samplePlaylist.copy(videos = Seq(sampleVideo, video2))
    val playlistDao = new StubPlaylistDao(
      findByIdResult = (id, _) => if (id == "playlist-1") Some(updatedPlaylist) else None
    )

    val service = createService(playlistDao = playlistDao)

    service.updatePlaylist("playlist-1", None, None, Some(Seq("video-1", "video-2")), None).map { result =>
      result.videos.size mustBe 2
    }
  }

  it should "filter by user ID when provided" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    implicit val randomGenerator: RandomGenerator[IO, UUID] = stubUuidGenerator(testUuid)

    val playlistDao = new StubPlaylistDao(
      findByIdResult = (id, userId) =>
        if (id == "playlist-1" && userId.contains("user-1")) Some(samplePlaylist) else None
    )

    val service = createService(playlistDao = playlistDao)

    service.updatePlaylist("playlist-1", Some("New Title"), None, None, Some("user-1")).map { result =>
      result.id mustBe "playlist-1"
    }
  }

  it should "raise ResourceNotFoundException when playlist not found" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    implicit val randomGenerator: RandomGenerator[IO, UUID] = stubUuidGenerator(testUuid)

    val playlistDao = new StubPlaylistDao(findByIdResult = (_, _) => None)
    val service = createService(playlistDao = playlistDao)

    service.updatePlaylist("non-existent", Some("Title"), None, None, None).error.map { error =>
      error mustBe a[ResourceNotFoundException]
      error.getMessage must include("non-existent")
    }
  }

  // fetchById tests
  "fetchById" should "return playlist when found" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    implicit val randomGenerator: RandomGenerator[IO, UUID] = stubUuidGenerator(testUuid)

    val playlistDao = new StubPlaylistDao(
      findByIdResult = (id, _) => if (id == "playlist-1") Some(samplePlaylist) else None
    )

    val service = createService(playlistDao = playlistDao)

    service.fetchById("playlist-1", None).map { result =>
      result mustBe samplePlaylist
    }
  }

  it should "filter by user ID when provided" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    implicit val randomGenerator: RandomGenerator[IO, UUID] = stubUuidGenerator(testUuid)

    val playlistDao = new StubPlaylistDao(
      findByIdResult = (id, userId) =>
        if (id == "playlist-1" && userId.contains("user-1")) Some(samplePlaylist) else None
    )

    val service = createService(playlistDao = playlistDao)

    service.fetchById("playlist-1", Some("user-1")).map { result =>
      result mustBe samplePlaylist
    }
  }

  it should "raise ResourceNotFoundException when not found" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    implicit val randomGenerator: RandomGenerator[IO, UUID] = stubUuidGenerator(testUuid)

    val playlistDao = new StubPlaylistDao(findByIdResult = (_, _) => None)
    val service = createService(playlistDao = playlistDao)

    service.fetchById("non-existent", None).error.map { error =>
      error mustBe a[ResourceNotFoundException]
      error.getMessage must include("non-existent")
    }
  }

  // search tests
  "search" should "return search results" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    implicit val randomGenerator: RandomGenerator[IO, UUID] = stubUuidGenerator(testUuid)

    val playlistDao = new StubPlaylistDao(searchResult = Seq(samplePlaylist))
    val service = createService(playlistDao = playlistDao)

    service.search(Some("test"), 10, 0, Order.Descending, PlaylistSortBy.CreatedAt, None).map { result =>
      result mustBe Seq(samplePlaylist)
    }
  }

  it should "handle empty search results" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    implicit val randomGenerator: RandomGenerator[IO, UUID] = stubUuidGenerator(testUuid)

    val playlistDao = new StubPlaylistDao(searchResult = Seq.empty)
    val service = createService(playlistDao = playlistDao)

    service.search(None, 10, 0, Order.Ascending, PlaylistSortBy.Title, Some("user-1")).map { result =>
      result mustBe empty
    }
  }

  it should "return multiple playlists" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    implicit val randomGenerator: RandomGenerator[IO, UUID] = stubUuidGenerator(testUuid)

    val playlist2 = samplePlaylist.copy(id = "playlist-2", title = "Second Playlist")
    val playlistDao = new StubPlaylistDao(searchResult = Seq(samplePlaylist, playlist2))
    val service = createService(playlistDao = playlistDao)

    service.search(None, 10, 0, Order.Descending, PlaylistSortBy.CreatedAt, None).map { result =>
      result.size mustBe 2
    }
  }

  // addAlbumArt tests
  "addAlbumArt" should "add album art to playlist" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    implicit val randomGenerator: RandomGenerator[IO, UUID] = stubUuidGenerator(testUuid)

    val playlistWithAlbumArt = samplePlaylist.copy(albumArt = Some(sampleAlbumArt))
    val playlistDao = new StubPlaylistDao(
      findByIdResult = (id, _) => if (id == "playlist-1") Some(playlistWithAlbumArt) else None,
      updateResult = 1
    )

    val repositoryService = new StubRepositoryService(sizeResult = Some(50000L))

    val service = createService(
      playlistDao = playlistDao,
      repositoryService = repositoryService
    )

    val imageData = Stream.emits[IO, Byte](Array[Byte](1, 2, 3))

    service.addAlbumArt("playlist-1", "test-image.jpg", MediaType.image.jpeg, imageData, None).map { result =>
      result.albumArt mustBe defined
    }
  }

  it should "raise error when file size cannot be determined" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    implicit val randomGenerator: RandomGenerator[IO, UUID] = stubUuidGenerator(testUuid)

    val playlistDao = new StubPlaylistDao(
      findByIdResult = (id, _) => if (id == "playlist-1") Some(samplePlaylist) else None
    )

    val repositoryService = new StubRepositoryService(sizeResult = None)

    val service = createService(
      playlistDao = playlistDao,
      repositoryService = repositoryService
    )

    val imageData = Stream.emits[IO, Byte](Array[Byte](1, 2, 3))

    service.addAlbumArt("playlist-1", "test-image.jpg", MediaType.image.jpeg, imageData, None).error.map { error =>
      error mustBe an[InvalidConditionException]
      error.getMessage must include("Unable to find saved file")
    }
  }

  it should "raise ResourceNotFoundException when playlist not found after update" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    implicit val randomGenerator: RandomGenerator[IO, UUID] = stubUuidGenerator(testUuid)

    val playlistDao = new StubPlaylistDao(
      findByIdResult = (_, _) => None,
      updateResult = 0
    )

    val repositoryService = new StubRepositoryService(sizeResult = Some(50000L))

    val service = createService(
      playlistDao = playlistDao,
      repositoryService = repositoryService
    )

    val imageData = Stream.emits[IO, Byte](Array[Byte](1, 2, 3))

    service.addAlbumArt("playlist-1", "test-image.jpg", MediaType.image.jpeg, imageData, None).error.map { error =>
      error mustBe a[ResourceNotFoundException]
    }
  }

  it should "filter by user ID when provided" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    implicit val randomGenerator: RandomGenerator[IO, UUID] = stubUuidGenerator(testUuid)

    val playlistWithAlbumArt = samplePlaylist.copy(albumArt = Some(sampleAlbumArt))
    val playlistDao = new StubPlaylistDao(
      findByIdResult = (id, userId) =>
        if (id == "playlist-1" && userId.contains("user-1")) Some(playlistWithAlbumArt) else None,
      updateResult = 1
    )

    val repositoryService = new StubRepositoryService(sizeResult = Some(50000L))

    val service = createService(
      playlistDao = playlistDao,
      repositoryService = repositoryService
    )

    val imageData = Stream.emits[IO, Byte](Array[Byte](1, 2, 3))

    service.addAlbumArt("playlist-1", "test-image.jpg", MediaType.image.jpeg, imageData, Some("user-1")).map { result =>
      result.albumArt mustBe defined
    }
  }

  // removeAlbumArt tests
  "removeAlbumArt" should "remove album art from playlist" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    implicit val randomGenerator: RandomGenerator[IO, UUID] = stubUuidGenerator(testUuid)

    val playlistWithoutAlbumArt = samplePlaylist.copy(albumArt = None)
    val playlistDao = new StubPlaylistDao(
      findByIdResult = (id, _) => if (id == "playlist-1") Some(playlistWithoutAlbumArt) else None,
      updateResult = 1
    )

    val service = createService(playlistDao = playlistDao)

    service.removeAlbumArt("playlist-1", None).map { result =>
      result.albumArt mustBe None
    }
  }

  it should "filter by user ID when provided" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    implicit val randomGenerator: RandomGenerator[IO, UUID] = stubUuidGenerator(testUuid)

    val playlistWithoutAlbumArt = samplePlaylist.copy(albumArt = None)
    val playlistDao = new StubPlaylistDao(
      findByIdResult = (id, userId) =>
        if (id == "playlist-1" && userId.contains("user-1")) Some(playlistWithoutAlbumArt) else None,
      updateResult = 1
    )

    val service = createService(playlistDao = playlistDao)

    service.removeAlbumArt("playlist-1", Some("user-1")).map { result =>
      result.albumArt mustBe None
    }
  }

  it should "raise ResourceNotFoundException when playlist not found" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    implicit val randomGenerator: RandomGenerator[IO, UUID] = stubUuidGenerator(testUuid)

    val playlistDao = new StubPlaylistDao(
      findByIdResult = (_, _) => None,
      updateResult = 0
    )

    val service = createService(playlistDao = playlistDao)

    service.removeAlbumArt("non-existent", None).error.map { error =>
      error mustBe a[ResourceNotFoundException]
    }
  }

  // deletePlaylist tests
  "deletePlaylist" should "delete playlist and return it" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    implicit val randomGenerator: RandomGenerator[IO, UUID] = stubUuidGenerator(testUuid)

    val playlistDao = new StubPlaylistDao(
      findByIdResult = (id, _) => if (id == "playlist-1") Some(samplePlaylist) else None,
      deleteResult = 1
    )

    val service = createService(playlistDao = playlistDao)

    service.deletePlaylist("playlist-1", None).map { result =>
      result mustBe samplePlaylist
    }
  }

  it should "filter by user ID when provided" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    implicit val randomGenerator: RandomGenerator[IO, UUID] = stubUuidGenerator(testUuid)

    val playlistDao = new StubPlaylistDao(
      findByIdResult = (id, userId) =>
        if (id == "playlist-1" && userId.contains("user-1")) Some(samplePlaylist) else None,
      deleteResult = 1
    )

    val service = createService(playlistDao = playlistDao)

    service.deletePlaylist("playlist-1", Some("user-1")).map { result =>
      result mustBe samplePlaylist
    }
  }

  it should "raise ResourceNotFoundException when playlist not found" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    implicit val randomGenerator: RandomGenerator[IO, UUID] = stubUuidGenerator(testUuid)

    val playlistDao = new StubPlaylistDao(findByIdResult = (_, _) => None)
    val service = createService(playlistDao = playlistDao)

    service.deletePlaylist("non-existent", None).error.map { error =>
      error mustBe a[ResourceNotFoundException]
      error.getMessage must include("non-existent")
    }
  }

  it should "return ResourceNotFoundException when user doesn't have access" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    implicit val randomGenerator: RandomGenerator[IO, UUID] = stubUuidGenerator(testUuid)

    val playlistDao = new StubPlaylistDao(
      findByIdResult = (id, userId) =>
        if (id == "playlist-1" && userId.isEmpty) Some(samplePlaylist) else None
    )

    val service = createService(playlistDao = playlistDao)

    service.deletePlaylist("playlist-1", Some("wrong-user")).error.map { error =>
      error mustBe a[ResourceNotFoundException]
    }
  }
}
