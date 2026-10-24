package com.ruchij.api.web.routes

import cats.effect.IO
import com.ruchij.api.daos.user.models.User
import com.ruchij.api.services.authentication.AuthenticationService.Secret
import com.ruchij.api.services.authentication.models.AuthenticationToken
import com.ruchij.api.services.scheduling.models.ScheduledVideoDeletion
import com.ruchij.api.test.data.ApiTestData
import com.ruchij.api.test.matchers._
import com.ruchij.api.test.mixins.io.MockedRoutesIO
import com.ruchij.core.daos.scheduling.models.{ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.services.scheduling.models.DownloadProgress
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.test.data.CoreTestData
import com.ruchij.core.types.TimeUtils
import fs2.{Pull, Stream}
import io.circe.parser.parse
import org.http4s.dsl.io._
import org.http4s.headers.Authorization
import org.http4s.implicits.http4sLiteralsSyntax
import org.http4s.{AuthScheme, Credentials, Headers, Request, ServerSentEvent, Status}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.duration._

class SchedulingUpdatesRoutesSpec extends AnyFlatSpec with Matchers with MockedRoutesIO {

  private val testTimestamp = TimeUtils.instantOf(2022, 8, 1, 10, 10)
  private val testSecret = Secret("test-secret-uuid")

  private val OwnedVideoId = "owned-video"
  private val OtherVideoId = "other-users-video"
  private val DeletedVideoId = "deleted-video"

  private def scheduledVideoDownload(videoId: String): ScheduledVideoDownload =
    ScheduledVideoDownload(
      scheduledAt = testTimestamp,
      lastUpdatedAt = testTimestamp,
      status = SchedulingStatus.Active,
      downloadedBytes = 0,
      videoMetadata = CoreTestData.YouTubeVideo.videoMetadata.copy(id = videoId),
      completedAt = None,
      errorInfo = None
    )

  override val downloadProgressStream: Stream[IO, DownloadProgress] =
    Stream(
      DownloadProgress(OwnedVideoId, testTimestamp, 1),
      DownloadProgress(OtherVideoId, testTimestamp, 2),
      DownloadProgress(OwnedVideoId, testTimestamp, 3),
      DownloadProgress(OtherVideoId, testTimestamp, 4)
    ).covary[IO]

  private def deleted(videoId: String): ScheduledVideoDownload =
    scheduledVideoDownload(videoId).copy(status = SchedulingStatus.Deleted)

  // An admin's delete of the normal user's video has removed its permissions by the time its Deleted update arrives
  override val scheduledVideoDownloadUpdatesStream: Stream[IO, ScheduledVideoDownload] =
    Stream(scheduledVideoDownload(OwnedVideoId), scheduledVideoDownload(OtherVideoId), deleted(DeletedVideoId))
      .covary[IO]

  override val scheduledVideoDeletionsStream: Stream[IO, ScheduledVideoDeletion] =
    Stream(
      ScheduledVideoDeletion(deleted(DeletedVideoId), List(ApiTestData.NormalUser.id)),
      ScheduledVideoDeletion(deleted(OtherVideoId), List("another-user"))
    ).covary[IO]

  private def authenticateAs(user: User): IO[Unit] =
    IO.delay {
      (authenticationService.authenticate _)
        .expects(testSecret)
        .returns {
          IO.pure {
            AuthenticationToken(user.id, testSecret, testTimestamp.plusSeconds(3600), testTimestamp, 0, testTimestamp) ->
              user
          }
        }
    }

  private def statusOf(serverSentEvent: ServerSentEvent): Option[String] =
    serverSentEvent.data.flatMap(data => parse(data).toOption).flatMap(_.hcursor.get[String]("status").toOption)

  private def videoIdOf(serverSentEvent: ServerSentEvent): Option[String] =
    serverSentEvent.data.flatMap(data => parse(data).toOption).flatMap { json =>
      json.hcursor
        .get[String]("videoId")
        .orElse(json.hcursor.downField("videoMetadata").get[String]("id"))
        .toOption
    }

  private def receivedEvents(expected: Int): IO[List[(Option[String], Option[String])]] =
    receivedEventsWithStatus(expected).map(_.map { case (eventType, videoId, _) => eventType -> videoId })

  /**
    * Reads events until the expected number has arrived, however long that takes on a slow machine, and then for
    * another second, so that an unexpected extra event shows up in the count. The heart-beats never end the stream.
    */
  private def receivedEventsWithStatus(expected: Int): IO[List[(Option[String], Option[String], Option[String])]] =
    createRoutes()
      .run(
        Request[IO](
          method = GET,
          uri = uri"/schedule/updates",
          headers = Headers(Authorization(Credentials.Token(AuthScheme.Bearer, testSecret.value)))
        )
      )
      .flatMap { response =>
        IO.delay(response must haveStatus(Status.Ok)) *>
          response.body
            .through(ServerSentEvent.decoder[IO])
            .filter(_.eventType.exists(_ != "heart-beat"))
            .pull
            .take(expected.toLong)
            .flatMap {
              case Some(rest) => rest.interruptAfter(1.second).pull.echo
              case None => Pull.done
            }
            .stream
            .compile
            .toList
            .timeout(30.seconds)
            .map(_.map(event => (event.eventType, videoIdOf(event), statusOf(event))))
      }

  "GET /schedule/updates" should "only stream events of downloads owned by a non-admin user" in runIO {
    authenticateAs(ApiTestData.NormalUser) *>
      IO.delay {
        // The ownership of each video is looked up once and cached for the lifetime of the connection
        (apiSchedulingService.hasPermission _)
          .expects(OwnedVideoId, ApiTestData.NormalUser.id)
          .once()
          .returns(IO.pure(true))

        (apiSchedulingService.hasPermission _)
          .expects(OtherVideoId, ApiTestData.NormalUser.id)
          .once()
          .returns(IO.pure(false))
      } *>
      ignoreHttpMetrics() *>
      receivedEvents(expected = 4).flatMap { events =>
        IO.delay {
          events.flatMap(_._2).toSet mustBe Set(OwnedVideoId, DeletedVideoId)
          events.count(_._1.contains("active-download")) mustBe 2
          events.count(_._1.contains("scheduled-video-download-update")) mustBe 2
        }
      }
  }

  it should "stream the events of every download to an admin user without permission look ups" in runIO {
    authenticateAs(ApiTestData.AdminUser) *>
      IO.delay {
        (apiSchedulingService.hasPermission _).expects(*, *).never()
      } *>
      ignoreHttpMetrics() *>
      receivedEvents(expected = 7).flatMap { events =>
        IO.delay {
          events.flatMap(_._2).toSet mustBe Set(OwnedVideoId, OtherVideoId, DeletedVideoId)
          events.count(_._1.contains("active-download")) mustBe 4
          events.count(_._1.contains("scheduled-video-download-update")) mustBe 3
        }
      }
  }

  it should "send a non-admin user the Deleted update of a video they had after its permissions are gone" in
    runIO {
      authenticateAs(ApiTestData.NormalUser) *>
        IO.delay {
          (apiSchedulingService.hasPermission _)
            .expects(OwnedVideoId, ApiTestData.NormalUser.id)
            .returns(IO.pure(true))

          (apiSchedulingService.hasPermission _)
            .expects(OtherVideoId, ApiTestData.NormalUser.id)
            .returns(IO.pure(false))
        } *>
        ignoreHttpMetrics() *>
        receivedEventsWithStatus(expected = 4).flatMap { events =>
          IO.delay {
            events.collect { case (_, videoId, Some("Deleted")) => videoId } mustBe List(Some(DeletedVideoId))
          }
        }
    }
}
