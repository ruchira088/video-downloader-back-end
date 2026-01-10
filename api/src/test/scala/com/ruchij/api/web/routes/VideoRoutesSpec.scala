package com.ruchij.api.web.routes

import cats.effect.IO
import com.ruchij.api.services.authentication.AuthenticationService.Secret
import com.ruchij.api.services.authentication.models.AuthenticationToken
import com.ruchij.api.test.data.ApiTestData
import com.ruchij.api.test.matchers._
import com.ruchij.api.test.mixins.io.MockedRoutesIO
import com.ruchij.core.daos.scheduling.models.RangeValue
import com.ruchij.core.daos.snapshot.models.Snapshot
import com.ruchij.core.exceptions.ResourceNotFoundException
import com.ruchij.core.services.models.{Order, SortBy}
import com.ruchij.core.services.video.VideoAnalysisService.{Existing, NewlyCreated}
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.test.data.CoreTestData
import io.circe.literal._
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.io._
import org.http4s.headers.Authorization
import org.http4s.implicits.http4sLiteralsSyntax
import org.http4s.{AuthScheme, Credentials, Request, Status}
import org.joda.time.{DateTime, DateTimeZone}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.duration._

class VideoRoutesSpec extends AnyFlatSpec with Matchers with MockedRoutesIO {

  private val testTimestamp = new DateTime(2022, 8, 1, 10, 10, 0, 0, DateTimeZone.UTC)
  private val expiresAt = testTimestamp.plusDays(45)
  private val testSecret = Secret("test-secret-uuid")
  private val normalUserToken = AuthenticationToken(
    ApiTestData.NormalUser.id,
    testSecret,
    expiresAt,
    testTimestamp,
    0
  )

  private def authHeaders = org.http4s.Headers(
    Authorization(Credentials.Token(AuthScheme.Bearer, testSecret.value))
  )

  "GET /video/search" should "return paginated search results" in runIO {
    (authenticationService.authenticate _)
      .expects(testSecret)
      .returns(IO.pure((normalUserToken, ApiTestData.NormalUser)))

    (apiVideoService.search _)
      .expects(
        None,
        None,
        RangeValue[FiniteDuration](None, None),
        RangeValue[Long](None, None),
        0,
        25,
        SortBy.Date,
        Order.Descending,
        None,
        Some(ApiTestData.NormalUser.id)
      )
      .returns(IO.pure(Seq(CoreTestData.YouTubeVideo)))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = GET,
            uri = uri"/videos/search",
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

  it should "support search term filter" in runIO {
    (authenticationService.authenticate _)
      .expects(testSecret)
      .returns(IO.pure((normalUserToken, ApiTestData.NormalUser)))

    (apiVideoService.search _)
      .expects(
        Some("test"),
        None,
        RangeValue[FiniteDuration](None, None),
        RangeValue[Long](None, None),
        0,
        25,
        SortBy.Date,
        Order.Descending,
        None,
        Some(ApiTestData.NormalUser.id)
      )
      .returns(IO.pure(Seq.empty))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = GET,
            uri = uri"/videos/search?search-term=test",
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

    (apiVideoService.search _)
      .expects(
        None,
        None,
        RangeValue[FiniteDuration](None, None),
        RangeValue[Long](None, None),
        2,
        5,
        SortBy.Size,
        Order.Ascending,
        None,
        Some(ApiTestData.NormalUser.id)
      )
      .returns(IO.pure(Seq.empty))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = GET,
            uri = uri"/videos/search?page-number=2&page-size=5&sort-by=size&order=asc",
            headers = authHeaders
          )
        )
        .flatMap { response =>
          IO.delay {
            response must haveStatus(Status.Ok)
          }
        }
  }

  "GET /video/id/:videoId" should "return a video by id" in runIO {
    (authenticationService.authenticate _)
      .expects(testSecret)
      .returns(IO.pure((normalUserToken, ApiTestData.NormalUser)))

    (apiVideoService.fetchById _)
      .expects("youtube-7488acd8", Some(ApiTestData.NormalUser.id))
      .returns(IO.pure(CoreTestData.YouTubeVideo))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = GET,
            uri = uri"/videos/id/youtube-7488acd8",
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

  it should "return not found when video does not exist" in runIO {
    (authenticationService.authenticate _)
      .expects(testSecret)
      .returns(IO.pure((normalUserToken, ApiTestData.NormalUser)))

    (apiVideoService.fetchById _)
      .expects("nonexistent-video", Some(ApiTestData.NormalUser.id))
      .returns(IO.raiseError(ResourceNotFoundException("Video not found: nonexistent-video")))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = GET,
            uri = uri"/videos/id/nonexistent-video",
            headers = authHeaders
          )
        )
        .flatMap { response =>
          IO.delay {
            response must haveStatus(Status.NotFound)
          }
        }
  }

  "DELETE /video/id/:videoId" should "delete a video" in runIO {
    (authenticationService.authenticate _)
      .expects(testSecret)
      .returns(IO.pure((normalUserToken, ApiTestData.NormalUser)))

    (apiVideoService.deleteById _)
      .expects("youtube-7488acd8", Some(ApiTestData.NormalUser.id), false)
      .returns(IO.pure(CoreTestData.YouTubeVideo))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = DELETE,
            uri = uri"/videos/id/youtube-7488acd8",
            headers = authHeaders
          )
        )
        .flatMap { response =>
          IO.delay {
            response must haveStatus(Status.Ok)
          }
        }
  }

  it should "delete video with file when requested" in runIO {
    (authenticationService.authenticate _)
      .expects(testSecret)
      .returns(IO.pure((normalUserToken, ApiTestData.NormalUser)))

    (apiVideoService.deleteById _)
      .expects("youtube-7488acd8", Some(ApiTestData.NormalUser.id), true)
      .returns(IO.pure(CoreTestData.YouTubeVideo))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = DELETE,
            uri = uri"/videos/id/youtube-7488acd8?delete-video-file=true",
            headers = authHeaders
          )
        )
        .flatMap { response =>
          IO.delay {
            response must haveStatus(Status.Ok)
          }
        }
  }

  "PATCH /video/id/:videoId/metadata" should "update video metadata" in runIO {
    (authenticationService.authenticate _)
      .expects(testSecret)
      .returns(IO.pure((normalUserToken, ApiTestData.NormalUser)))

    val updatedVideo = CoreTestData.YouTubeVideo

    (apiVideoService.update _)
      .expects("youtube-7488acd8", "Updated Title", Some(ApiTestData.NormalUser.id))
      .returns(IO.pure(updatedVideo))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = PATCH,
            uri = uri"/videos/id/youtube-7488acd8/metadata",
            headers = authHeaders
          ).withEntity(json"""{"title": "Updated Title"}""")
        )
        .flatMap { response =>
          IO.delay {
            response must haveStatus(Status.Ok)
          }
        }
  }

  "POST /video/metadata" should "return existing metadata when already analyzed" in runIO {
    (authenticationService.authenticate _)
      .expects(testSecret)
      .returns(IO.pure((normalUserToken, ApiTestData.NormalUser)))

    (videoAnalysisService.metadata _)
      .expects(uri"https://www.youtube.com/watch?v=S5n9emOr7SQ")
      .returns(IO.pure(Existing(CoreTestData.YouTubeVideo.videoMetadata)))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = POST,
            uri = uri"/videos/metadata",
            headers = authHeaders
          ).withEntity(json"""{"url": "https://www.youtube.com/watch?v=S5n9emOr7SQ"}""")
        )
        .flatMap { response =>
          IO.delay {
            response must beJsonContentType
            response must haveStatus(Status.Ok)
          }
        }
  }

  it should "return created when new metadata is fetched" in runIO {
    (authenticationService.authenticate _)
      .expects(testSecret)
      .returns(IO.pure((normalUserToken, ApiTestData.NormalUser)))

    (videoAnalysisService.metadata _)
      .expects(uri"https://www.youtube.com/watch?v=new123")
      .returns(IO.pure(NewlyCreated(CoreTestData.YouTubeVideo.videoMetadata)))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = POST,
            uri = uri"/videos/metadata",
            headers = authHeaders
          ).withEntity(json"""{"url": "https://www.youtube.com/watch?v=new123"}""")
        )
        .flatMap { response =>
          IO.delay {
            response must beJsonContentType
            response must haveStatus(Status.Created)
          }
        }
  }

  "GET /video/id/:videoId/snapshots" should "return video snapshots" in runIO {
    (authenticationService.authenticate _)
      .expects(testSecret)
      .returns(IO.pure((normalUserToken, ApiTestData.NormalUser)))

    (apiVideoService.fetchById _)
      .expects("youtube-7488acd8", Some(ApiTestData.NormalUser.id))
      .returns(IO.pure(CoreTestData.YouTubeVideo))

    val snapshot = Snapshot(
      "youtube-7488acd8",
      CoreTestData.YouTubeVideo.videoMetadata.thumbnail,
      5.seconds
    )

    (apiVideoService.fetchVideoSnapshots _)
      .expects("youtube-7488acd8", Some(ApiTestData.NormalUser.id))
      .returns(IO.pure(Seq(snapshot)))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = GET,
            uri = uri"/videos/id/youtube-7488acd8/snapshots",
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

  "GET /video/scan" should "return forbidden for non-admin users" in runIO {
    (authenticationService.authenticate _)
      .expects(testSecret)
      .returns(IO.pure((normalUserToken, ApiTestData.NormalUser)))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = GET,
            uri = uri"/videos/scan",
            headers = authHeaders
          )
        )
        .flatMap { response =>
          IO.delay {
            response must haveStatus(Status.Forbidden)
          }
        }
  }
}
