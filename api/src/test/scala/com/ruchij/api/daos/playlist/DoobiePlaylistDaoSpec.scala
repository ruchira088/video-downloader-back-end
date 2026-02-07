package com.ruchij.api.daos.playlist

import cats.effect.IO
import com.ruchij.api.daos.playlist.models.{Playlist, PlaylistSortBy}
import com.ruchij.api.test.data.ApiTestData
import com.ruchij.core.daos.resource.DoobieFileResourceDao
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.video.DoobieVideoDao
import com.ruchij.core.external.embedded.EmbeddedCoreResourcesProvider
import com.ruchij.core.services.models.Order
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.test.data.CoreTestData
import org.http4s.MediaType
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.time.Duration
import scala.concurrent.ExecutionContext.Implicits.global

class DoobiePlaylistDaoSpec extends AnyFlatSpec with Matchers with OptionValues {

  val doobiePlaylistDao = new DoobiePlaylistDao(DoobieFileResourceDao, DoobieVideoDao)

  val albumArtFileResource: FileResource = FileResource(
    "album-art-001",
    CoreTestData.Timestamp,
    "/home/images/album-art.jpg",
    MediaType.image.jpeg,
    50000
  )

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

  it should "insert playlist with album art" in runIO {
    new EmbeddedCoreResourcesProvider[IO].transactor.use { implicit transactor =>
      for {
        _ <- transactor(ApiTestData.setUpData)
        _ <- transactor(DoobieFileResourceDao.insert(albumArtFileResource))

        playlistWithAlbumArt = Playlist(
          "playlist-with-art",
          ApiTestData.AdminUser.id,
          CoreTestData.Timestamp,
          "Playlist With Album Art",
          Some("A playlist with artwork"),
          List(CoreTestData.YouTubeVideo),
          Some(albumArtFileResource)
        )

        insertionResult <- transactor(doobiePlaylistDao.insert(playlistWithAlbumArt))
        _ <- IO.delay { insertionResult mustBe 2 }

        maybeFetchedPlaylist <- transactor(doobiePlaylistDao.findById("playlist-with-art", None))
        _ <- IO.delay {
          maybeFetchedPlaylist mustBe defined
          maybeFetchedPlaylist.value.albumArt mustBe Some(albumArtFileResource)
        }

        _ <- transactor(doobiePlaylistDao.deleteById("playlist-with-art", None))
      } yield ()
    }
  }

  it should "update playlist with album art" in runIO {
    new EmbeddedCoreResourcesProvider[IO].transactor.use { implicit transactor =>
      for {
        _ <- transactor(ApiTestData.setUpData)
        _ <- transactor(DoobieFileResourceDao.insert(albumArtFileResource))

        playlist = Playlist(
          "playlist-update-art",
          ApiTestData.AdminUser.id,
          CoreTestData.Timestamp,
          "Playlist To Update Art",
          None,
          List.empty,
          None
        )

        _ <- transactor(doobiePlaylistDao.insert(playlist))

        updateWithArtResult <- transactor {
          doobiePlaylistDao.update(
            "playlist-update-art",
            None,
            None,
            None,
            Some(Right(albumArtFileResource.id)),
            None
          )
        }
        _ <- IO.delay { updateWithArtResult mustBe 1 }

        maybeUpdatedWithArt <- transactor(doobiePlaylistDao.findById("playlist-update-art", None))
        _ <- IO.delay {
          maybeUpdatedWithArt.value.albumArt mustBe Some(albumArtFileResource)
        }

        updateRemoveArtResult <- transactor {
          doobiePlaylistDao.update(
            "playlist-update-art",
            None,
            None,
            None,
            Some(Left(())),
            None
          )
        }
        _ <- IO.delay { updateRemoveArtResult mustBe 1 }

        maybeUpdatedWithoutArt <- transactor(doobiePlaylistDao.findById("playlist-update-art", None))
        _ <- IO.delay {
          maybeUpdatedWithoutArt.value.albumArt mustBe None
        }

        _ <- transactor(doobiePlaylistDao.deleteById("playlist-update-art", None))
      } yield ()
    }
  }

  it should "update playlist with user ID filter" in runIO {
    new EmbeddedCoreResourcesProvider[IO].transactor.use { implicit transactor =>
      for {
        _ <- transactor(ApiTestData.setUpData)

        playlist = Playlist(
          "playlist-user-filter",
          ApiTestData.AdminUser.id,
          CoreTestData.Timestamp,
          "Original Title",
          None,
          List.empty,
          None
        )

        _ <- transactor(doobiePlaylistDao.insert(playlist))

        updateWithCorrectUser <- transactor {
          doobiePlaylistDao.update(
            "playlist-user-filter",
            Some("Updated Title"),
            None,
            None,
            None,
            Some(ApiTestData.AdminUser.id)
          )
        }
        _ <- IO.delay { updateWithCorrectUser mustBe 1 }

        maybeUpdated <- transactor(doobiePlaylistDao.findById("playlist-user-filter", None))
        _ <- IO.delay { maybeUpdated.value.title mustBe "Updated Title" }

        updateWithWrongUser <- transactor {
          doobiePlaylistDao.update(
            "playlist-user-filter",
            Some("Should Not Update"),
            None,
            None,
            None,
            Some(ApiTestData.NormalUser.id)
          )
        }
        _ <- IO.delay { updateWithWrongUser mustBe 0 }

        maybeNotUpdated <- transactor(doobiePlaylistDao.findById("playlist-user-filter", None))
        _ <- IO.delay { maybeNotUpdated.value.title mustBe "Updated Title" }

        _ <- transactor(doobiePlaylistDao.deleteById("playlist-user-filter", None))
      } yield ()
    }
  }

  it should "return 0 when updating with no fields to update" in runIO {
    new EmbeddedCoreResourcesProvider[IO].transactor.use { implicit transactor =>
      for {
        _ <- transactor(ApiTestData.setUpData)

        playlist = Playlist(
          "playlist-no-update",
          ApiTestData.AdminUser.id,
          CoreTestData.Timestamp,
          "No Update Playlist",
          None,
          List.empty,
          None
        )

        _ <- transactor(doobiePlaylistDao.insert(playlist))

        updateResult <- transactor {
          doobiePlaylistDao.update(
            "playlist-no-update",
            None,
            None,
            None,
            None,
            None
          )
        }
        _ <- IO.delay { updateResult mustBe 0 }

        _ <- transactor(doobiePlaylistDao.deleteById("playlist-no-update", None))
      } yield ()
    }
  }

  it should "search with user ID filter" in runIO {
    new EmbeddedCoreResourcesProvider[IO].transactor.use { implicit transactor =>
      for {
        _ <- transactor(ApiTestData.setUpData)

        adminPlaylist = Playlist(
          "admin-playlist",
          ApiTestData.AdminUser.id,
          CoreTestData.Timestamp,
          "Admin Playlist",
          None,
          List.empty,
          None
        )

        normalUserPlaylist = Playlist(
          "normal-user-playlist",
          ApiTestData.NormalUser.id,
          CoreTestData.Timestamp,
          "Normal User Playlist",
          None,
          List.empty,
          None
        )

        _ <- transactor(doobiePlaylistDao.insert(adminPlaylist))
        _ <- transactor(doobiePlaylistDao.insert(normalUserPlaylist))

        searchWithAdminUser <- transactor {
          doobiePlaylistDao.search(None, 10, 0, Order.Descending, PlaylistSortBy.CreatedAt, Some(ApiTestData.AdminUser.id))
        }
        _ <- IO.delay {
          searchWithAdminUser.size mustBe 1
          searchWithAdminUser.head.id mustBe "admin-playlist"
        }

        searchWithNormalUser <- transactor {
          doobiePlaylistDao.search(None, 10, 0, Order.Descending, PlaylistSortBy.CreatedAt, Some(ApiTestData.NormalUser.id))
        }
        _ <- IO.delay {
          searchWithNormalUser.size mustBe 1
          searchWithNormalUser.head.id mustBe "normal-user-playlist"
        }

        searchWithoutUserFilter <- transactor {
          doobiePlaylistDao.search(None, 10, 0, Order.Descending, PlaylistSortBy.CreatedAt, None)
        }
        _ <- IO.delay {
          searchWithoutUserFilter.size mustBe 2
        }

        _ <- transactor(doobiePlaylistDao.deleteById("admin-playlist", None))
        _ <- transactor(doobiePlaylistDao.deleteById("normal-user-playlist", None))
      } yield ()
    }
  }

  it should "search with different sort by options" in runIO {
    new EmbeddedCoreResourcesProvider[IO].transactor.use { implicit transactor =>
      for {
        _ <- transactor(ApiTestData.setUpData)

        playlist1 = Playlist(
          "playlist-a",
          ApiTestData.AdminUser.id,
          CoreTestData.Timestamp,
          "Alpha Playlist",
          None,
          List.empty,
          None
        )

        playlist2 = Playlist(
          "playlist-b",
          ApiTestData.AdminUser.id,
          CoreTestData.Timestamp.plus(Duration.ofHours(1)),
          "Zeta Playlist",
          None,
          List.empty,
          None
        )

        _ <- transactor(doobiePlaylistDao.insert(playlist1))
        _ <- transactor(doobiePlaylistDao.insert(playlist2))

        searchByTitleAsc <- transactor {
          doobiePlaylistDao.search(None, 10, 0, Order.Ascending, PlaylistSortBy.Title, None)
        }
        _ <- IO.delay {
          searchByTitleAsc.head.title mustBe "Alpha Playlist"
        }

        searchByTitleDesc <- transactor {
          doobiePlaylistDao.search(None, 10, 0, Order.Descending, PlaylistSortBy.Title, None)
        }
        _ <- IO.delay {
          searchByTitleDesc.head.title mustBe "Zeta Playlist"
        }

        searchByCreatedAtAsc <- transactor {
          doobiePlaylistDao.search(None, 10, 0, Order.Ascending, PlaylistSortBy.CreatedAt, None)
        }
        _ <- IO.delay {
          searchByCreatedAtAsc.head.id mustBe "playlist-a"
        }

        searchByCreatedAtDesc <- transactor {
          doobiePlaylistDao.search(None, 10, 0, Order.Descending, PlaylistSortBy.CreatedAt, None)
        }
        _ <- IO.delay {
          searchByCreatedAtDesc.head.id mustBe "playlist-b"
        }

        _ <- transactor(doobiePlaylistDao.deleteById("playlist-a", None))
        _ <- transactor(doobiePlaylistDao.deleteById("playlist-b", None))
      } yield ()
    }
  }

  it should "support pagination in search" in runIO {
    new EmbeddedCoreResourcesProvider[IO].transactor.use { implicit transactor =>
      for {
        _ <- transactor(ApiTestData.setUpData)

        playlist1 = Playlist("page-1", ApiTestData.AdminUser.id, CoreTestData.Timestamp, "Playlist 1", None, List.empty, None)
        playlist2 = Playlist("page-2", ApiTestData.AdminUser.id, CoreTestData.Timestamp.plus(Duration.ofHours(1)), "Playlist 2", None, List.empty, None)
        playlist3 = Playlist("page-3", ApiTestData.AdminUser.id, CoreTestData.Timestamp.plus(Duration.ofHours(2)), "Playlist 3", None, List.empty, None)

        _ <- transactor(doobiePlaylistDao.insert(playlist1))
        _ <- transactor(doobiePlaylistDao.insert(playlist2))
        _ <- transactor(doobiePlaylistDao.insert(playlist3))

        page0 <- transactor {
          doobiePlaylistDao.search(None, 2, 0, Order.Descending, PlaylistSortBy.CreatedAt, None)
        }
        _ <- IO.delay { page0.size mustBe 2 }

        page1 <- transactor {
          doobiePlaylistDao.search(None, 2, 1, Order.Descending, PlaylistSortBy.CreatedAt, None)
        }
        _ <- IO.delay { page1.size mustBe 1 }

        _ <- transactor(doobiePlaylistDao.deleteById("page-1", None))
        _ <- transactor(doobiePlaylistDao.deleteById("page-2", None))
        _ <- transactor(doobiePlaylistDao.deleteById("page-3", None))
      } yield ()
    }
  }

  it should "delete playlist with user ID filter" in runIO {
    new EmbeddedCoreResourcesProvider[IO].transactor.use { implicit transactor =>
      for {
        _ <- transactor(ApiTestData.setUpData)

        playlist = Playlist(
          "delete-with-user",
          ApiTestData.AdminUser.id,
          CoreTestData.Timestamp,
          "Delete With User",
          None,
          List(CoreTestData.YouTubeVideo),
          None
        )

        _ <- transactor(doobiePlaylistDao.insert(playlist))

        deleteWithWrongUser <- transactor {
          doobiePlaylistDao.deleteById("delete-with-user", Some(ApiTestData.NormalUser.id))
        }
        _ <- IO.delay { deleteWithWrongUser mustBe 0 }

        stillExists <- transactor(doobiePlaylistDao.findById("delete-with-user", None))
        _ <- IO.delay { stillExists mustBe defined }

        deleteWithCorrectUser <- transactor {
          doobiePlaylistDao.deleteById("delete-with-user", Some(ApiTestData.AdminUser.id))
        }
        _ <- IO.delay { deleteWithCorrectUser mustBe 2 }

        noLongerExists <- transactor(doobiePlaylistDao.findById("delete-with-user", None))
        _ <- IO.delay { noLongerExists mustBe None }
      } yield ()
    }
  }

  it should "insert playlist with no videos" in runIO {
    new EmbeddedCoreResourcesProvider[IO].transactor.use { implicit transactor =>
      for {
        _ <- transactor(ApiTestData.setUpData)

        emptyPlaylist = Playlist(
          "empty-playlist",
          ApiTestData.AdminUser.id,
          CoreTestData.Timestamp,
          "Empty Playlist",
          None,
          List.empty,
          None
        )

        insertionResult <- transactor(doobiePlaylistDao.insert(emptyPlaylist))
        _ <- IO.delay { insertionResult mustBe 1 }

        maybeFetchedPlaylist <- transactor(doobiePlaylistDao.findById("empty-playlist", None))
        _ <- IO.delay {
          maybeFetchedPlaylist mustBe defined
          maybeFetchedPlaylist.value.videos mustBe empty
        }

        _ <- transactor(doobiePlaylistDao.deleteById("empty-playlist", None))
      } yield ()
    }
  }

  it should "update playlist videos only" in runIO {
    new EmbeddedCoreResourcesProvider[IO].transactor.use { implicit transactor =>
      for {
        _ <- transactor(ApiTestData.setUpData)

        playlist = Playlist(
          "videos-only-update",
          ApiTestData.AdminUser.id,
          CoreTestData.Timestamp,
          "Videos Only Update",
          None,
          List(CoreTestData.YouTubeVideo),
          None
        )

        _ <- transactor(doobiePlaylistDao.insert(playlist))

        updateResult <- transactor {
          doobiePlaylistDao.update(
            "videos-only-update",
            None,
            None,
            Some(List(CoreTestData.SpankBangVideo.videoMetadata.id, CoreTestData.LocalVideo.videoMetadata.id)),
            None,
            None
          )
        }
        _ <- IO.delay { updateResult mustBe 3 }

        maybeUpdated <- transactor(doobiePlaylistDao.findById("videos-only-update", None))
        _ <- IO.delay {
          maybeUpdated.value.videos.map(_.videoMetadata.id).toSet mustBe
            Set(CoreTestData.SpankBangVideo.videoMetadata.id, CoreTestData.LocalVideo.videoMetadata.id)
        }

        _ <- transactor(doobiePlaylistDao.deleteById("videos-only-update", None))
      } yield ()
    }
  }
}
