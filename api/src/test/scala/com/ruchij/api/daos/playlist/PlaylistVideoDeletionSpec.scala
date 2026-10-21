package com.ruchij.api.daos.playlist

import cats.effect.IO
import cats.implicits._
import cats.~>
import com.ruchij.api.daos.playlist.models.Playlist
import com.ruchij.api.test.data.ApiTestData
import com.ruchij.core.daos.permission.DoobieVideoPermissionDao
import com.ruchij.core.daos.resource.DoobieFileResourceDao
import com.ruchij.core.daos.scheduling.DoobieSchedulingDao
import com.ruchij.core.daos.snapshot.DoobieSnapshotDao
import com.ruchij.core.daos.title.DoobieVideoTitleDao
import com.ruchij.core.daos.video.DoobieVideoDao
import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.daos.videometadata.DoobieVideoMetadataDao
import com.ruchij.core.daos.videowatchhistory.DoobieVideoWatchHistoryDao
import com.ruchij.core.external.CoreResourcesProvider
import com.ruchij.core.external.containers.ContainerCoreResourcesProvider
import com.ruchij.core.external.embedded.EmbeddedCoreResourcesProvider
import com.ruchij.core.services.repository.RepositoryService
import com.ruchij.core.services.video.VideoServiceImpl
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.test.data.CoreTestData
import doobie.free.connection.ConnectionIO
import fs2.Stream
import org.http4s.MediaType
import org.http4s.implicits.http4sLiteralsSyntax
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

/** Deleting a video must also remove it from every playlist that contains it. */
class PlaylistVideoDeletionSpec extends AnyFlatSpec with Matchers with OptionValues {

  private val providers: List[(String, () => CoreResourcesProvider[IO])] =
    List(
      "H2" -> (() => new EmbeddedCoreResourcesProvider[IO]),
      "PostgreSQL" -> (() => new ContainerCoreResourcesProvider[IO])
    )

  private val playlistDao = new DoobiePlaylistDao(DoobieFileResourceDao, DoobieVideoDao)

  private object NoOpRepositoryService extends RepositoryService[IO] {
    override type BackedType = String
    override def write(key: Key, data: Stream[IO, Byte]): Stream[IO, Nothing] = Stream.empty
    override def read(key: Key, start: Option[Long], end: Option[Long]): IO[Option[Stream[IO, Byte]]] = IO.pure(None)
    override def size(key: Key): IO[Option[Long]] = IO.pure(None)
    override def list(key: Key): Stream[IO, Key] = Stream.empty
    override def exists(key: Key): IO[Boolean] = IO.pure(false)
    override def backedType(key: Key): IO[BackedType] = IO.pure("none")
    override def delete(key: Key): IO[Boolean] = IO.pure(false)
    override def fileType(key: Key): IO[Option[MediaType]] = IO.pure(None)
  }

  private val playlist =
    Playlist(
      "playlist-with-videos",
      ApiTestData.NormalUser.id,
      CoreTestData.Timestamp,
      "Playlist",
      None,
      List(CoreTestData.YouTubeVideo, CoreTestData.SpankBangVideo),
      None
    )

  private def withPlaylist(provider: () => CoreResourcesProvider[IO])(test: (ConnectionIO ~> IO) => IO[Any]): Unit =
    runIO {
      provider().transactor.use { transaction =>
        transaction(ApiTestData.setUpData)
          .productR(transaction(playlistDao.insert(playlist)))
          .productR(test(transaction))
          .void
      }
    }

  private def videoService(implicit transaction: ConnectionIO ~> IO): VideoServiceImpl[IO, ConnectionIO] =
    new VideoServiceImpl[IO, ConnectionIO](
      NoOpRepositoryService,
      DoobieVideoDao,
      DoobieVideoWatchHistoryDao,
      DoobieSnapshotDao,
      DoobieFileResourceDao,
      DoobieVideoTitleDao,
      DoobieVideoPermissionDao,
      DoobieSchedulingDao
    )

  // An incorrectly completed download: under the 1MB that queueIncorrectlyCompletedVideos looks for
  private val smallVideo: Video =
    Video(
      CoreTestData.YouTubeVideo.videoMetadata.copy(
        url = uri"https://www.youtube.com/watch?v=small-video",
        id = "youtube-small-video",
        size = 1024,
        thumbnail = CoreTestData.YouTubeVideo.videoMetadata.thumbnail
          .copy(id = "youtube-small-video-thumbnail", path = "/home/images/youtube-small-video.jpg")
      ),
      CoreTestData.YouTubeVideo.fileResource
        .copy(id = "youtube-small-video", path = "/home/videos/youtube-small-video.mp4", size = 1024),
      CoreTestData.Timestamp,
      0.seconds
    )

  private val insertSmallVideoFile: ConnectionIO[Int] =
    DoobieFileResourceDao
      .insert(smallVideo.fileResource)
      .productR {
        DoobieVideoDao.insert(smallVideo.videoMetadata.id, smallVideo.fileResource.id, smallVideo.createdAt, 0.seconds)
      }

  private val insertSmallVideo: ConnectionIO[Int] =
    DoobieFileResourceDao
      .insert(smallVideo.videoMetadata.thumbnail)
      .productR(DoobieVideoMetadataDao.insert(smallVideo.videoMetadata))
      .productR(insertSmallVideoFile)

  private val playlistsWithSmallVideo: List[Playlist] =
    List(
      playlist.copy(
        id = "playlist-with-small-video",
        videos = List(CoreTestData.YouTubeVideo, smallVideo, CoreTestData.SpankBangVideo)
      ),
      playlist.copy(id = "other-user-playlist", userId = ApiTestData.AdminUser.id, videos = List(smallVideo))
    )

  private def playlistVideoIds(playlistId: String): ConnectionIO[List[String]] =
    playlistDao.findById(playlistId, None).map(_.toList.flatMap(_.videos.map(_.videoMetadata.id)))

  providers.foreach {
    case (databaseName, provider) =>
      s"VideoServiceImpl.queueIncorrectlyCompletedVideos ($databaseName)" should
        "keep the re-queued video in its playlists once it is downloaded again" in
        withPlaylist(provider) { implicit transaction =>
          val playlistId = playlistsWithSmallVideo.head.id
          val otherPlaylistId = playlistsWithSmallVideo.last.id

          for {
            _ <- transaction(insertSmallVideo)
            _ <- playlistsWithSmallVideo.traverse(playlist => transaction(playlistDao.insert(playlist)))

            requeued <- videoService.queueIncorrectlyCompletedVideos
            whileRequeued <- transaction(playlistVideoIds(playlistId))

            // The batch worker downloads the video again and saves it under the same ID
            _ <- transaction(insertSmallVideoFile)
            redownloaded <- transaction(playlistVideoIds(playlistId))
            otherRedownloaded <- transaction(playlistVideoIds(otherPlaylistId))
          } yield {
            requeued.map(_.videoMetadata.id) mustBe List(smallVideo.videoMetadata.id)
            whileRequeued mustBe List(CoreTestData.YouTubeVideo, CoreTestData.SpankBangVideo).map(_.videoMetadata.id)
            redownloaded mustBe
              List(CoreTestData.YouTubeVideo, smallVideo, CoreTestData.SpankBangVideo).map(_.videoMetadata.id)
            otherRedownloaded mustBe List(smallVideo.videoMetadata.id)
          }
        }

      it should "append the re-queued video when its position was taken while it was downloaded again" in
        withPlaylist(provider) { implicit transaction =>
          val playlistId = playlistsWithSmallVideo.head.id
          val reorderedVideoIds = List(CoreTestData.SpankBangVideo, CoreTestData.YouTubeVideo).map(_.videoMetadata.id)

          for {
            _ <- transaction(insertSmallVideo)
            _ <- transaction(playlistDao.insert(playlistsWithSmallVideo.head))

            _ <- videoService.queueIncorrectlyCompletedVideos
            _ <- transaction(playlistDao.update(playlistId, None, None, Some(reorderedVideoIds), None, None))

            _ <- transaction(insertSmallVideoFile)
            redownloaded <- transaction(playlistVideoIds(playlistId))
          } yield redownloaded mustBe reorderedVideoIds :+ smallVideo.videoMetadata.id
        }

      it should "not bring back a re-queued video that is deleted after it is downloaded again" in
        withPlaylist(provider) { implicit transaction =>
          val playlistId = playlistsWithSmallVideo.head.id

          for {
            _ <- transaction(insertSmallVideo)
            _ <- transaction(playlistDao.insert(playlistsWithSmallVideo.head))

            _ <- videoService.queueIncorrectlyCompletedVideos
            _ <- transaction(insertSmallVideoFile)
            _ <- videoService.deleteById(smallVideo.videoMetadata.id, deleteVideoFile = false)

            // Scheduled and downloaded again later
            _ <- transaction(insertSmallVideoFile)
            afterDelete <- transaction(playlistVideoIds(playlistId))
          } yield afterDelete mustBe
            List(CoreTestData.YouTubeVideo, CoreTestData.SpankBangVideo).map(_.videoMetadata.id)
        }

      s"VideoServiceImpl.deleteById ($databaseName)" should "delete a video that is in a playlist" in
        withPlaylist(provider) { implicit transaction =>
          for {
            deleted <- videoService.deleteById(CoreTestData.YouTubeVideo.videoMetadata.id, deleteVideoFile = false)
            remaining <- transaction(playlistDao.findById(playlist.id, None))
          } yield {
            deleted.videoMetadata.id mustBe CoreTestData.YouTubeVideo.videoMetadata.id
            remaining.value.videos.map(_.videoMetadata.id) mustBe List(CoreTestData.SpankBangVideo.videoMetadata.id)
          }
      }

      s"DoobieVideoDao.deleteById ($databaseName)" should "delete a video that is in a playlist" in
        withPlaylist(provider) { transaction =>
          for {
            // queueIncorrectlyCompletedVideos deletes videos through this DAO call
            _ <- transaction(DoobieVideoDao.deleteById(CoreTestData.SpankBangVideo.videoMetadata.id))
            remaining <- transaction(playlistDao.findById(playlist.id, None))
          } yield remaining.value.videos.map(_.videoMetadata.id) mustBe List(CoreTestData.YouTubeVideo.videoMetadata.id)
      }
  }
}
