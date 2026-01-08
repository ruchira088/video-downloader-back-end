package com.ruchij.api.web.routes

import cats.effect.IO
import com.ruchij.api.daos.playlist.models.{Playlist, PlaylistSortBy}
import com.ruchij.api.services.authentication.AuthenticationService.Secret
import com.ruchij.api.services.authentication.models.AuthenticationToken
import com.ruchij.api.test.data.ApiTestData
import com.ruchij.api.test.matchers._
import com.ruchij.api.test.mixins.io.MockedRoutesIO
import com.ruchij.core.exceptions.ResourceNotFoundException
import com.ruchij.core.services.models.Order
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.test.data.CoreTestData
import io.circe.literal._
import org.http4s.headers.Authorization
import org.http4s.{AuthScheme, Credentials, Request, Status}
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.client.dsl.io._
import org.http4s.dsl.io._
import org.http4s.implicits.http4sLiteralsSyntax
import org.joda.time.{DateTime, DateTimeZone}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class PlaylistRoutesSpec extends AnyFlatSpec with Matchers with MockedRoutesIO {

  private val testTimestamp = new DateTime(2022, 8, 1, 10, 10, 0, 0, DateTimeZone.UTC)
  private val expiresAt = testTimestamp.plusDays(45)
  private val testSecret = Secret("test-secret-uuid")
  private val adminToken = AuthenticationToken(
    ApiTestData.AdminUser.id,
    testSecret,
    expiresAt,
    testTimestamp,
    0
  )
  private val normalUserToken = AuthenticationToken(
    ApiTestData.NormalUser.id,
    testSecret,
    expiresAt,
    testTimestamp,
    0
  )

  private val testPlaylist = Playlist(
    id = "playlist-123",
    userId = ApiTestData.NormalUser.id,
    createdAt = testTimestamp,
    title = "My Playlist",
    description = Some("Test playlist description"),
    videos = Seq.empty,
    albumArt = None
  )

  private def authHeaders = org.http4s.Headers(
    Authorization(Credentials.Token(AuthScheme.Bearer, testSecret.value))
  )

  "POST /playlist" should "create a new playlist successfully" in runIO {
    (authenticationService.authenticate _)
      .expects(testSecret)
      .returns(IO.pure((normalUserToken, ApiTestData.NormalUser)))

    (playlistService.create _)
      .expects("My Playlist", Some("Test description"), ApiTestData.NormalUser.id)
      .returns(IO.pure(testPlaylist.copy(description = Some("Test description"))))

    val expectedJsonResponse =
      json"""{
        "id": "playlist-123",
        "userId": "alice.doe",
        "createdAt": "2022-08-01T10:10:00.000Z",
        "title": "My Playlist",
        "description": "Test description",
        "videos": [],
        "albumArt": null
      }"""

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = POST,
            uri = uri"/playlists",
            headers = authHeaders
          ).withEntity(json"""{"title": "My Playlist", "description": "Test description"}""")
        )
        .flatMap { response =>
          IO.delay {
            response must beJsonContentType
            response must haveJson(expectedJsonResponse)
            response must haveStatus(Status.Created)
          }
        }
  }

  it should "create a playlist without description" in runIO {
    (authenticationService.authenticate _)
      .expects(testSecret)
      .returns(IO.pure((normalUserToken, ApiTestData.NormalUser)))

    (playlistService.create _)
      .expects("My Playlist", None, ApiTestData.NormalUser.id)
      .returns(IO.pure(testPlaylist.copy(description = None)))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = POST,
            uri = uri"/playlists",
            headers = authHeaders
          ).withEntity(json"""{"title": "My Playlist"}""")
        )
        .flatMap { response =>
          IO.delay {
            response must haveStatus(Status.Created)
          }
        }
  }

  it should "return unauthorized when not authenticated" in runIO {
    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          POST(
            json"""{"title": "My Playlist"}""",
            uri"/playlists"
          )
        )
        .flatMap { response =>
          IO.delay {
            response must haveStatus(Status.Unauthorized)
          }
        }
  }

  "GET /playlist" should "return paginated playlists" in runIO {
    (authenticationService.authenticate _)
      .expects(testSecret)
      .returns(IO.pure((normalUserToken, ApiTestData.NormalUser)))

    (playlistService.search _)
      .expects(None, 25, 0, Order.Descending, PlaylistSortBy.CreatedAt, Some(ApiTestData.NormalUser.id))
      .returns(IO.pure(Seq(testPlaylist)))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = GET,
            uri = uri"/playlists",
            headers = authHeaders
          )
        )
        .flatMap { response =>
          IO.delay {
            response must beJsonContentType
            response must haveStatus(Status.Ok)
          }
        }
  }

  it should "support search term query parameter" in runIO {
    (authenticationService.authenticate _)
      .expects(testSecret)
      .returns(IO.pure((normalUserToken, ApiTestData.NormalUser)))

    (playlistService.search _)
      .expects(Some("test"), 25, 0, Order.Descending, PlaylistSortBy.CreatedAt, Some(ApiTestData.NormalUser.id))
      .returns(IO.pure(Seq(testPlaylist)))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = GET,
            uri = uri"/playlists?search-term=test",
            headers = authHeaders
          )
        )
        .flatMap { response =>
          IO.delay {
            response must haveStatus(Status.Ok)
          }
        }
  }

  it should "support pagination parameters" in runIO {
    (authenticationService.authenticate _)
      .expects(testSecret)
      .returns(IO.pure((normalUserToken, ApiTestData.NormalUser)))

    (playlistService.search _)
      .expects(None, 5, 2, Order.Ascending, PlaylistSortBy.Title, Some(ApiTestData.NormalUser.id))
      .returns(IO.pure(Seq.empty))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = GET,
            uri = uri"/playlists?page-size=5&page-number=2&order=asc&sort-by=Title",
            headers = authHeaders
          )
        )
        .flatMap { response =>
          IO.delay {
            response must haveStatus(Status.Ok)
          }
        }
  }

  "GET /playlist/id/:playlistId" should "return a playlist by id" in runIO {
    (authenticationService.authenticate _)
      .expects(testSecret)
      .returns(IO.pure((normalUserToken, ApiTestData.NormalUser)))

    (playlistService.fetchById _)
      .expects("playlist-123", Some(ApiTestData.NormalUser.id))
      .returns(IO.pure(testPlaylist))

    val expectedJsonResponse =
      json"""{
        "id": "playlist-123",
        "userId": "alice.doe",
        "createdAt": "2022-08-01T10:10:00.000Z",
        "title": "My Playlist",
        "description": "Test playlist description",
        "videos": [],
        "albumArt": null
      }"""

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = GET,
            uri = uri"/playlists/id/playlist-123",
            headers = authHeaders
          )
        )
        .flatMap { response =>
          IO.delay {
            response must beJsonContentType
            response must haveJson(expectedJsonResponse)
            response must haveStatus(Status.Ok)
          }
        }
  }

  it should "return not found when playlist does not exist" in runIO {
    (authenticationService.authenticate _)
      .expects(testSecret)
      .returns(IO.pure((normalUserToken, ApiTestData.NormalUser)))

    (playlistService.fetchById _)
      .expects("nonexistent-playlist", Some(ApiTestData.NormalUser.id))
      .returns(IO.raiseError(ResourceNotFoundException("Playlist not found: nonexistent-playlist")))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = GET,
            uri = uri"/playlists/id/nonexistent-playlist",
            headers = authHeaders
          )
        )
        .flatMap { response =>
          IO.delay {
            response must haveStatus(Status.NotFound)
          }
        }
  }

  "PUT /playlist/id/:playlistId" should "update a playlist successfully" in runIO {
    (authenticationService.authenticate _)
      .expects(testSecret)
      .returns(IO.pure((normalUserToken, ApiTestData.NormalUser)))

    val updatedPlaylist = testPlaylist.copy(title = "Updated Title", description = Some("Updated description"))

    (playlistService.updatePlaylist _)
      .expects("playlist-123", Some("Updated Title"), Some("Updated description"), None, Some(ApiTestData.NormalUser.id))
      .returns(IO.pure(updatedPlaylist))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = PUT,
            uri = uri"/playlists/id/playlist-123",
            headers = authHeaders
          ).withEntity(json"""{"title": "Updated Title", "description": "Updated description"}""")
        )
        .flatMap { response =>
          IO.delay {
            response must haveStatus(Status.Ok)
          }
        }
  }

  it should "update playlist with video list" in runIO {
    (authenticationService.authenticate _)
      .expects(testSecret)
      .returns(IO.pure((normalUserToken, ApiTestData.NormalUser)))

    val playlistWithVideos = testPlaylist.copy(videos = Seq(CoreTestData.YouTubeVideo))

    (playlistService.updatePlaylist _)
      .expects("playlist-123", None, None, Some(Seq("video-1", "video-2")), Some(ApiTestData.NormalUser.id))
      .returns(IO.pure(playlistWithVideos))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = PUT,
            uri = uri"/playlists/id/playlist-123",
            headers = authHeaders
          ).withEntity(json"""{"videoIds": ["video-1", "video-2"]}""")
        )
        .flatMap { response =>
          IO.delay {
            response must haveStatus(Status.Ok)
          }
        }
  }

  "DELETE /playlist/id/:playlistId" should "delete a playlist successfully" in runIO {
    (authenticationService.authenticate _)
      .expects(testSecret)
      .returns(IO.pure((normalUserToken, ApiTestData.NormalUser)))

    (playlistService.deletePlaylist _)
      .expects("playlist-123", Some(ApiTestData.NormalUser.id))
      .returns(IO.pure(testPlaylist))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = DELETE,
            uri = uri"/playlists/id/playlist-123",
            headers = authHeaders
          )
        )
        .flatMap { response =>
          IO.delay {
            response must haveStatus(Status.Ok)
          }
        }
  }

  "DELETE /playlist/id/:playlistId/album-art" should "remove album art from playlist" in runIO {
    (authenticationService.authenticate _)
      .expects(testSecret)
      .returns(IO.pure((normalUserToken, ApiTestData.NormalUser)))

    (playlistService.removeAlbumArt _)
      .expects("playlist-123", Some(ApiTestData.NormalUser.id))
      .returns(IO.pure(testPlaylist))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = DELETE,
            uri = uri"/playlists/id/playlist-123/album-art",
            headers = authHeaders
          )
        )
        .flatMap { response =>
          IO.delay {
            response must haveStatus(Status.Ok)
          }
        }
  }

  "Admin user" should "be able to access all playlists" in runIO {
    (authenticationService.authenticate _)
      .expects(testSecret)
      .returns(IO.pure((adminToken, ApiTestData.AdminUser)))

    (playlistService.search _)
      .expects(None, 25, 0, Order.Descending, PlaylistSortBy.CreatedAt, None)
      .returns(IO.pure(Seq(testPlaylist)))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = GET,
            uri = uri"/playlists",
            headers = authHeaders
          )
        )
        .flatMap { response =>
          IO.delay {
            response must haveStatus(Status.Ok)
          }
        }
  }
}
