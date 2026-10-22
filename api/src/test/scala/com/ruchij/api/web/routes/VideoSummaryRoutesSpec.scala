package com.ruchij.api.web.routes

import cats.data.NonEmptyList
import cats.effect.IO
import com.ruchij.api.services.authentication.AuthenticationService.Secret
import com.ruchij.api.services.authentication.models.AuthenticationToken
import com.ruchij.api.services.video.ApiVideoService
import com.ruchij.api.test.data.ApiTestData
import com.ruchij.api.test.matchers._
import com.ruchij.api.test.mixins.io.MockedRoutesIO
import com.ruchij.core.daos.scheduling.models.RangeValue
import com.ruchij.core.daos.snapshot.models.Snapshot
import com.ruchij.core.daos.video.models.{Video, VideoSiteSummary}
import com.ruchij.core.daos.videometadata.models.{CustomVideoSite, VideoSite}
import com.ruchij.core.daos.workers.models.VideoScan
import com.ruchij.core.services.models.{Order, SortBy}
import com.ruchij.core.services.video.models.VideoServiceSummary
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.types.TimeUtils
import io.circe.Json
import io.circe.literal._
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.dsl.io._
import org.http4s.headers.Authorization
import org.http4s.implicits.http4sLiteralsSyntax
import org.http4s.{AuthScheme, Credentials, Request, Status, Uri}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.duration._

/**
  * The summary is a `val` on [[ApiVideoService]], which the mocked service cannot stub, so this suite replaces the
  * service with one that only answers the summary.
  */
class VideoSummaryRoutesSpec extends AnyFlatSpec with Matchers with MockedRoutesIO {

  private val testTimestamp = TimeUtils.instantOf(2022, 8, 1, 10, 10)
  private val testSecret = Secret("test-secret-uuid")
  private val adminToken = AuthenticationToken(
    ApiTestData.AdminUser.id,
    testSecret,
    testTimestamp.plus(java.time.Duration.ofDays(45)),
    testTimestamp,
    0,
    testTimestamp
  )

  private val videoServiceSummary =
    VideoServiceSummary(
      videoCount = 3,
      totalSize = 6000L,
      totalDuration = 30.minutes,
      sites = Set(CustomVideoSite.SpankBang, CustomVideoSite.PornOne),
      siteBreakdown = Seq(
        VideoSiteSummary(CustomVideoSite.SpankBang, 2, 5000L, 20.minutes, 90.seconds),
        VideoSiteSummary(CustomVideoSite.PornOne, 1, 1000L, 10.minutes, 0.seconds)
      )
    )

  override val apiVideoService: ApiVideoService[IO] = new ApiVideoService[IO] {
    override def fetchById(videoId: String, maybeUserId: Option[String]): IO[Video] = unexpected

    override def fetchVideoSnapshots(videoId: String, maybeUserId: Option[String]): IO[Seq[Snapshot]] = unexpected

    override def update(videoId: String, title: String, maybeUserId: Option[String]): IO[Video] = unexpected

    override def deleteById(videoId: String, maybeUserId: Option[String], deleteVideoFile: Boolean): IO[Video] =
      unexpected

    override def search(
      term: Option[String],
      videoUrls: Option[NonEmptyList[Uri]],
      durationRange: RangeValue[FiniteDuration],
      sizeRange: RangeValue[Long],
      pageNumber: Int,
      pageSize: Int,
      sortBy: SortBy,
      order: Order,
      videoSites: Option[NonEmptyList[VideoSite]],
      maybeUserId: Option[String]
    ): IO[Seq[Video]] = unexpected

    override val summary: IO[VideoServiceSummary] = IO.pure(videoServiceSummary)

    override val scanForVideos: IO[VideoScan] = unexpected

    override val scanStatus: IO[Option[VideoScan]] = unexpected

    override val queueIncorrectlyCompletedVideos: IO[Seq[Video]] = unexpected
  }

  private def unexpected[A]: IO[A] = IO.raiseError(new IllegalStateException("Unexpected call"))

  "GET /videos/summary" should "return the summary with a per-site breakdown for admin users" in runIO {
    (authenticationService.authenticate _)
      .expects(testSecret)
      .returns(IO.pure((adminToken, ApiTestData.AdminUser)))

    val expectedJson =
      json"""{
        "videoCount": 3,
        "totalSize": 6000,
        "totalDuration": { "length": 30, "unit": "MINUTES" },
        "sites": ["spankbang", "pornone"],
        "siteBreakdown": [
          {
            "site": "spankbang",
            "videoCount": 2,
            "totalSize": 5000,
            "totalDuration": { "length": 20, "unit": "MINUTES" },
            "watchTime": { "length": 90, "unit": "SECONDS" }
          },
          {
            "site": "pornone",
            "videoCount": 1,
            "totalSize": 1000,
            "totalDuration": { "length": 10, "unit": "MINUTES" },
            "watchTime": { "length": 0, "unit": "SECONDS" }
          }
        ]
      }"""

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = GET,
            uri = uri"/videos/summary",
            headers = org.http4s.Headers(Authorization(Credentials.Token(AuthScheme.Bearer, testSecret.value)))
          )
        )
        .flatMap { response =>
          IO.delay {
            response must beJsonContentType
            response must haveStatus(Status.Ok)
          } *> response.as[Json].flatMap { body =>
            IO.delay {
              body.hcursor.downField("sites").as[Set[String]] mustBe Right(Set("spankbang", "pornone"))
              body.mapObject(_.remove("sites")) mustBe expectedJson.mapObject(_.remove("sites"))
            }
          }
        }
  }
}
