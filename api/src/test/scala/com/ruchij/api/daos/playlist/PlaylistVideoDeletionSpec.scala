package com.ruchij.api.daos.playlist

import cats.effect.IO
import cats.~>
import com.ruchij.api.daos.playlist.models.Playlist
import com.ruchij.api.test.data.ApiTestData
import com.ruchij.core.daos.permission.DoobieVideoPermissionDao
import com.ruchij.core.daos.resource.DoobieFileResourceDao
import com.ruchij.core.daos.scheduling.DoobieSchedulingDao
import com.ruchij.core.daos.snapshot.DoobieSnapshotDao
import com.ruchij.core.daos.title.DoobieVideoTitleDao
import com.ruchij.core.daos.video.DoobieVideoDao
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
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.ExecutionContext.Implicits.global

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

  providers.foreach {
    case (databaseName, provider) =>
      s"VideoServiceImpl.deleteById ($databaseName)" should "delete a video that is in a playlist" in
        withPlaylist(provider) { implicit transaction =>
          val videoService =
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
