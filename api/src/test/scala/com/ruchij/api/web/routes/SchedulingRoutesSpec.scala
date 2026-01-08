package com.ruchij.api.web.routes

import cats.effect.IO
import com.ruchij.api.services.authentication.AuthenticationService.Secret
import com.ruchij.api.services.authentication.models.AuthenticationToken
import com.ruchij.api.services.scheduling.models.ScheduledVideoResult.{AlreadyScheduled, NewlyScheduled}
import com.ruchij.api.test.data.ApiTestData
import com.ruchij.api.test.matchers._
import com.ruchij.api.test.mixins.io.MockedRoutesIO
import com.ruchij.core.daos.scheduling.models.{RangeValue, ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.daos.workers.models.WorkerStatus
import com.ruchij.core.exceptions.ResourceNotFoundException
import com.ruchij.core.services.models.{Order, SortBy}
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.test.data.CoreTestData
import io.circe.literal._
import org.http4s.{AuthScheme, Credentials, Request, Status}
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.io._
import org.http4s.headers.Authorization
import org.http4s.implicits.http4sLiteralsSyntax
import org.joda.time.{DateTime, DateTimeZone}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.duration._

class SchedulingRoutesSpec extends AnyFlatSpec with Matchers with MockedRoutesIO {

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

  private val testScheduledDownload = ScheduledVideoDownload(
    scheduledAt = testTimestamp,
    lastUpdatedAt = testTimestamp,
    status = SchedulingStatus.Active,
    downloadedBytes = 0,
    videoMetadata = CoreTestData.YouTubeVideo.videoMetadata,
    completedAt = None,
    errorInfo = None
  )

  private def authHeaders = org.http4s.Headers(
    Authorization(Credentials.Token(AuthScheme.Bearer, testSecret.value))
  )

  "POST /schedule" should "schedule a new video download" in runIO {
    (authenticationService.authenticate _)
      .expects(testSecret)
      .returns(IO.pure((normalUserToken, ApiTestData.NormalUser)))

    (apiSchedulingService.schedule _)
      .expects(uri"https://www.youtube.com/watch?v=test123", ApiTestData.NormalUser.id)
      .returns(IO.pure(NewlyScheduled(testScheduledDownload)))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = POST,
            uri = uri"/schedule",
            headers = authHeaders
          ).withEntity(json"""{"url": "https://www.youtube.com/watch?v=test123"}""")
        )
        .flatMap { response =>
          IO.delay {
            response must beJsonContentType
            response must haveStatus(Status.Created)
          }
        }
  }

  it should "return Ok when video is already scheduled" in runIO {
    (authenticationService.authenticate _)
      .expects(testSecret)
      .returns(IO.pure((normalUserToken, ApiTestData.NormalUser)))

    (apiSchedulingService.schedule _)
      .expects(uri"https://www.youtube.com/watch?v=existing", ApiTestData.NormalUser.id)
      .returns(IO.pure(AlreadyScheduled(testScheduledDownload)))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = POST,
            uri = uri"/schedule",
            headers = authHeaders
          ).withEntity(json"""{"url": "https://www.youtube.com/watch?v=existing"}""")
        )
        .flatMap { response =>
          IO.delay {
            response must beJsonContentType
            response must haveStatus(Status.Ok)
          }
        }
  }

  "POST /schedule/retry-failed" should "retry failed downloads" in runIO {
    (authenticationService.authenticate _)
      .expects(testSecret)
      .returns(IO.pure((normalUserToken, ApiTestData.NormalUser)))

    val failedDownload = testScheduledDownload.copy(status = SchedulingStatus.Error)

    (apiSchedulingService.retryFailed _)
      .expects(Some(ApiTestData.NormalUser.id))
      .returns(IO.pure(Seq(failedDownload)))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = POST,
            uri = uri"/schedule/retry-failed",
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

  "GET /schedule/search" should "return paginated scheduled downloads" in runIO {
    (authenticationService.authenticate _)
      .expects(testSecret)
      .returns(IO.pure((normalUserToken, ApiTestData.NormalUser)))

    (apiSchedulingService.search _)
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
        None,
        Some(ApiTestData.NormalUser.id)
      )
      .returns(IO.pure(Seq(testScheduledDownload)))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = GET,
            uri = uri"/schedule/search",
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

  it should "support filtering by status" in runIO {
    (authenticationService.authenticate _)
      .expects(testSecret)
      .returns(IO.pure((normalUserToken, ApiTestData.NormalUser)))

    (apiSchedulingService.search _)
      .expects(*, *, *, *, *, *, *, *, *, *, *)
      .returns(IO.pure(Seq.empty))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = GET,
            uri = uri"/schedule/search?status=Active",
            headers = authHeaders
          )
        )
        .flatMap { response =>
          IO.delay {
            response must haveStatus(Status.Ok)
          }
        }
  }

  "GET /schedule/id/:videoId" should "return a scheduled download by id" in runIO {
    (authenticationService.authenticate _)
      .expects(testSecret)
      .returns(IO.pure((normalUserToken, ApiTestData.NormalUser)))

    (apiSchedulingService.getById _)
      .expects("youtube-7488acd8", Some(ApiTestData.NormalUser.id))
      .returns(IO.pure(testScheduledDownload))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = GET,
            uri = uri"/schedule/id/youtube-7488acd8",
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

  it should "return not found when scheduled download does not exist" in runIO {
    (authenticationService.authenticate _)
      .expects(testSecret)
      .returns(IO.pure((normalUserToken, ApiTestData.NormalUser)))

    (apiSchedulingService.getById _)
      .expects("nonexistent", Some(ApiTestData.NormalUser.id))
      .returns(IO.raiseError(ResourceNotFoundException("Scheduled download not found: nonexistent")))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = GET,
            uri = uri"/schedule/id/nonexistent",
            headers = authHeaders
          )
        )
        .flatMap { response =>
          IO.delay {
            response must haveStatus(Status.NotFound)
          }
        }
  }

  "DELETE /schedule/id/:videoId" should "delete a scheduled download" in runIO {
    (authenticationService.authenticate _)
      .expects(testSecret)
      .returns(IO.pure((normalUserToken, ApiTestData.NormalUser)))

    (apiSchedulingService.deleteById _)
      .expects("youtube-7488acd8", Some(ApiTestData.NormalUser.id))
      .returns(IO.pure(testScheduledDownload))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = DELETE,
            uri = uri"/schedule/id/youtube-7488acd8",
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

  "PUT /schedule/id/:videoId" should "update scheduling status" in runIO {
    (authenticationService.authenticate _)
      .expects(testSecret)
      .returns(IO.pure((normalUserToken, ApiTestData.NormalUser)))

    val pausedDownload = testScheduledDownload.copy(status = SchedulingStatus.Paused)

    (apiSchedulingService.updateSchedulingStatus _)
      .expects("youtube-7488acd8", SchedulingStatus.Paused)
      .returns(IO.pure(pausedDownload))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = PUT,
            uri = uri"/schedule/id/youtube-7488acd8",
            headers = authHeaders
          ).withEntity(json"""{"status": "Paused"}""")
        )
        .flatMap { response =>
          IO.delay {
            response must beJsonContentType
            response must haveStatus(Status.Ok)
          }
        }
  }

  "PUT /schedule/worker-status" should "update worker status" in runIO {
    (authenticationService.authenticate _)
      .expects(testSecret)
      .returns(IO.pure((adminToken, ApiTestData.AdminUser)))

    (apiSchedulingService.updateWorkerStatus _)
      .expects(WorkerStatus.Paused)
      .returns(IO.unit)

    val expectedJsonResponse = json"""{"workerStatus": "Paused"}"""

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = PUT,
            uri = uri"/schedule/worker-status",
            headers = authHeaders
          ).withEntity(json"""{"workerStatus": "Paused"}""")
        )
        .flatMap { response =>
          IO.delay {
            response must beJsonContentType
            response must haveJson(expectedJsonResponse)
            response must haveStatus(Status.Ok)
          }
        }
  }

  "Admin user" should "be able to access all scheduled downloads" in runIO {
    (authenticationService.authenticate _)
      .expects(testSecret)
      .returns(IO.pure((adminToken, ApiTestData.AdminUser)))

    (apiSchedulingService.search _)
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
        None,
        None
      )
      .returns(IO.pure(Seq(testScheduledDownload)))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = GET,
            uri = uri"/schedule/search",
            headers = authHeaders
          )
        )
        .flatMap { response =>
          IO.delay {
            response must haveStatus(Status.Ok)
          }
        }
  }

  it should "be able to retry all failed downloads" in runIO {
    (authenticationService.authenticate _)
      .expects(testSecret)
      .returns(IO.pure((adminToken, ApiTestData.AdminUser)))

    (apiSchedulingService.retryFailed _)
      .expects(None)
      .returns(IO.pure(Seq.empty))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = POST,
            uri = uri"/schedule/retry-failed",
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
