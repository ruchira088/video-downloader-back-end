package com.ruchij.api.daos.playlist

import cats.effect.IO
import com.ruchij.api.daos.playlist.models.{Playlist, PlaylistSortBy}
import com.ruchij.api.test.data.ApiTestData
import com.ruchij.core.daos.resource.DoobieFileResourceDao
import com.ruchij.core.daos.video.DoobieVideoDao
import com.ruchij.core.external.embedded.EmbeddedCoreResourcesProvider
import com.ruchij.core.services.models.Order
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.test.data.CoreTestData
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.ExecutionContext.Implicits.global

class DoobiePlaylistDaoSpec extends AnyFlatSpec with Matchers with OptionValues {

  val doobiePlaylistDao = new DoobiePlaylistDao(DoobieFileResourceDao, DoobieVideoDao)

  "DoobiePlaylistDao" should "perform CRUD operations" in runIO {
    new EmbeddedCoreResourcesProvider[IO].transactor.use { implicit transactor =>
      for {
        _ <- transactor(ApiTestData.setUpData)

        playlist = Playlist(
          "my-playlist-id",
          ApiTestData.AdminUser.id,
          CoreTestData.Timestamp,
          "My Playlist",
          Some("This is an awesome playlist"),
          List(CoreTestData.YouTubeVideo, CoreTestData.SpankBangVideo),
          None
        )

        insertionResult <- transactor(doobiePlaylistDao.insert(playlist))
        _ <- IO.delay { insertionResult mustBe 3 }

        maybeFetchedPlaylist <- transactor(doobiePlaylistDao.findById("my-playlist-id", None))
        _ <- IO.delay { maybeFetchedPlaylist mustBe Some(playlist) }

        maybeFetchedPlaylistWithUser <- transactor {
          doobiePlaylistDao.findById("my-playlist-id", Some(ApiTestData.AdminUser.id))
        }
        _ <- IO.delay { maybeFetchedPlaylistWithUser mustBe Some(playlist) }

        maybeFetchedPlaylistWithWrongUser <- transactor {
          doobiePlaylistDao.findById("my-playlist-id", Some(ApiTestData.NormalUser.id))
        }
        _ <- IO.delay { maybeFetchedPlaylistWithWrongUser mustBe None }

        searchResultsWithTitle <- transactor {
          doobiePlaylistDao.search(Some("my"), 10, 0, Order.Descending, PlaylistSortBy.CreatedAt, None)
        }
        _ <- IO.delay { searchResultsWithTitle mustBe List(playlist)}

        searchResultsWithDescription <- transactor {
          doobiePlaylistDao.search(Some("awesome"), 10, 0, Order.Descending, PlaylistSortBy.CreatedAt, None)
        }
        _ <- IO.delay { searchResultsWithDescription mustBe List(playlist) }

        emptySearchResults <- transactor {
          doobiePlaylistDao.search(Some("random"), 10, 0, Order.Descending, PlaylistSortBy.CreatedAt, None)
        }
        _ <- IO.delay { emptySearchResults mustBe List.empty }

        updateResult <- transactor {
          doobiePlaylistDao.update(
            "my-playlist-id",
            Some("Updated Playlist Title"),
            Some("This is the new description"),
            Some(List(CoreTestData.SpankBangVideo.videoMetadata.id)),
            None,
            None
          )
        }
        _ <- IO.delay { updateResult mustBe 4 }

        maybeUpdatedPlaylist <- transactor(doobiePlaylistDao.findById("my-playlist-id", None))
        updatedPlaylist <- IO.delay(maybeUpdatedPlaylist.value)

        _ <- IO.delay {
          updatedPlaylist.title mustBe "Updated Playlist Title"
          updatedPlaylist.description mustBe Some("This is the new description")
          updatedPlaylist.videos mustBe List(CoreTestData.SpankBangVideo)
        }

        deleteResult <- transactor(doobiePlaylistDao.deleteById("my-playlist-id", None))
        _ <- IO.delay { deleteResult mustBe 2 }

        emptyFetchedResult <- transactor(doobiePlaylistDao.findById("my-playlist-id", None))
        _ <- IO.delay { emptyFetchedResult mustBe None }

      } yield (): Unit
    }
  }

}
