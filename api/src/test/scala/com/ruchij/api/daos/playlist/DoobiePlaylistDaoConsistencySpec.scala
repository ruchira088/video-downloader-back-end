package com.ruchij.api.daos.playlist

import cats.effect.IO
import cats.implicits._
import cats.~>
import com.ruchij.api.daos.playlist.models.{Playlist, PlaylistSortBy}
import com.ruchij.api.test.data.ApiTestData
import com.ruchij.core.daos.resource.DoobieFileResourceDao
import com.ruchij.core.daos.video.DoobieVideoDao
import com.ruchij.core.external.CoreResourcesProvider
import com.ruchij.core.external.containers.ContainerCoreResourcesProvider
import com.ruchij.core.external.embedded.EmbeddedCoreResourcesProvider
import com.ruchij.core.services.models.Order
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.test.data.CoreTestData
import doobie.free.connection.ConnectionIO
import doobie.free.{connection => FC}
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

/** Paging and concurrent updates of playlists, run against both H2 and PostgreSQL. */
class DoobiePlaylistDaoConsistencySpec extends AnyFlatSpec with Matchers with OptionValues {

  private val providers: List[(String, () => CoreResourcesProvider[IO])] =
    List(
      "H2" -> (() => new EmbeddedCoreResourcesProvider[IO]),
      "PostgreSQL" -> (() => new ContainerCoreResourcesProvider[IO])
    )

  private val playlistDao = new DoobiePlaylistDao(DoobieFileResourceDao, DoobieVideoDao)

  private def playlist(id: String, title: String): Playlist =
    Playlist(id, ApiTestData.AdminUser.id, CoreTestData.Timestamp, title, None, Nil, None)

  private def withData(provider: () => CoreResourcesProvider[IO])(test: (ConnectionIO ~> IO) => IO[Any]): Unit =
    runIO {
      provider().transactor.use { transaction =>
        transaction(ApiTestData.setUpData).productR(test(transaction)).void
      }
    }

  providers.foreach {
    case (databaseName, provider) =>
      s"DoobiePlaylistDao.search ($databaseName)" should "page through tied playlists without repeats or gaps" in
        withData(provider) { transaction =>
          // Inserted out of ID order, all with the same title and creation timestamp
          val playlistIds = List("playlist-c", "playlist-a", "playlist-e", "playlist-b", "playlist-d")

          def pages(sortBy: PlaylistSortBy, order: Order): IO[List[String]] =
            (0 until playlistIds.size).toList
              .traverse { pageNumber =>
                transaction(playlistDao.search(None, 1, pageNumber, order, sortBy, None))
              }
              .map(_.flatten.map(_.id))

          for {
            _ <- playlistIds.traverse(id => transaction(playlistDao.insert(playlist(id, "Same Title"))))

            titleAscending <- pages(PlaylistSortBy.Title, Order.Ascending)
            titleDescending <- pages(PlaylistSortBy.Title, Order.Descending)
            createdAtAscending <- pages(PlaylistSortBy.CreatedAt, Order.Ascending)
            createdAtDescending <- pages(PlaylistSortBy.CreatedAt, Order.Descending)
          } yield {
            titleAscending mustBe playlistIds.sorted
            titleDescending mustBe playlistIds.sorted.reverse
            createdAtAscending mustBe playlistIds.sorted
            createdAtDescending mustBe playlistIds.sorted.reverse
          }
        }

      s"DoobiePlaylistDao.update ($databaseName)" should "serialise concurrent video list updates" in
        withData(provider) { transaction =>
          val playlistId = "concurrently-updated-playlist"
          val youTubeVideoId = CoreTestData.YouTubeVideo.videoMetadata.id
          val spankBangVideoId = CoreTestData.SpankBangVideo.videoMetadata.id
          val localVideoId = CoreTestData.LocalVideo.videoMetadata.id

          def updateVideos(videoIds: List[String]): ConnectionIO[Int] =
            playlistDao.update(playlistId, None, None, Some(videoIds), None, None)

          // The first update holds its transaction open so that the second one runs while it is uncommitted
          val firstUpdate =
            transaction(updateVideos(List(youTubeVideoId, spankBangVideoId)).productL(FC.delay(Thread.sleep(500))))

          val secondUpdate =
            IO.sleep(150.millis).productR(transaction(updateVideos(List(spankBangVideoId, localVideoId))))

          for {
            _ <- transaction(playlistDao.insert(playlist(playlistId, "Concurrent Playlist")))
            _ <- (firstUpdate, secondUpdate).parTupled
            updated <- transaction(playlistDao.findById(playlistId, None))
          } yield updated.value.videos.map(_.videoMetadata.id) mustBe List(spankBangVideoId, localVideoId)
        }
  }
}
