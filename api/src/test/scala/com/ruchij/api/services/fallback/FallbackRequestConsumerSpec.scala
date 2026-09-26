package com.ruchij.api.services.fallback

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.kernel.Ref
import com.ruchij.api.daos.user.UserDao
import com.ruchij.api.daos.user.models.{Email, Role, User}
import com.ruchij.api.services.fallback.FallbackSyncStubs._
import com.ruchij.api.services.fallback.FallbackSyncTestData.{capturedAt, scheduledVideoDownload}
import com.ruchij.api.services.fallback.aws.{FallbackRequestQueue, ReceivedMessage}
import com.ruchij.api.services.fallback.models._
import com.ruchij.api.services.scheduling.ApiSchedulingService
import com.ruchij.api.services.scheduling.models.ScheduledVideoResult
import com.ruchij.core.daos.scheduling.models.{RangeValue, ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.daos.videometadata.models.VideoSite
import com.ruchij.core.daos.workers.models.WorkerStatus
import com.ruchij.core.exceptions.{ExternalServiceException, ValidationException}
import com.ruchij.core.services.models.{Order, SortBy}
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.test.Providers
import com.ruchij.core.types.Clock
import org.http4s.Uri
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

class FallbackRequestConsumerSpec extends AnyFlatSpec with Matchers {
  implicit val clock: Clock[IO] = Providers.stubClock[IO](capturedAt)

  private val request = ScheduleRequest("request-1", "user-1", "https://example.com/video", capturedAt)

  private def message(body: String, receiveCount: Int = 1) = ReceivedMessage(body, "receipt-1", receiveCount)

  private def body(scheduleRequest: ScheduleRequest): String =
    SyncJson.scheduleRequestEncoder(scheduleRequest).noSpaces

  private final class StubQueue(deleted: Ref[IO, List[String]]) extends FallbackRequestQueue[IO] {
    override val receive: IO[List[ReceivedMessage]] = IO.pure(Nil)
    override def delete(receiptHandle: String): IO[Unit] = deleted.update(_ :+ receiptHandle)
    val deletedHandles: IO[List[String]] = deleted.get
  }

  private def notUsed[A]: IO[A] = IO.raiseError(new NotImplementedError("not used by the consumer"))

  private def userDao(exists: Boolean): UserDao[IO] =
    new UserDao[IO] {
      override def insert(user: User): IO[Int] = notUsed
      override def findByEmail(email: Email): IO[Option[User]] = notUsed
      override def deleteById(userId: String): IO[Int] = notUsed

      override def findById(userId: String): IO[Option[User]] =
        IO.pure {
          if (exists) Some(User(userId, capturedAt, "Test", "User", Email(s"$userId@test.com"), Role.User))
          else None
        }
    }

  private def schedulingService(outcome: IO[ScheduledVideoResult]): ApiSchedulingService[IO] =
    new ApiSchedulingService[IO] {
      override def schedule(uri: Uri, userId: String): IO[ScheduledVideoResult] = outcome

      override def search(
        term: Option[String],
        videoUrls: Option[NonEmptyList[Uri]],
        durationRange: RangeValue[FiniteDuration],
        sizeRange: RangeValue[Long],
        pageNumber: Int,
        pageSize: Int,
        sortBy: SortBy,
        order: Order,
        schedulingStatuses: Option[NonEmptyList[SchedulingStatus]],
        videoSites: Option[NonEmptyList[VideoSite]],
        maybeUserId: Option[String]
      ): IO[Seq[ScheduledVideoDownload]] = notUsed

      override def retryFailed(maybeUserId: Option[String]): IO[Seq[ScheduledVideoDownload]] = notUsed

      override def updateSchedulingStatus(
        id: String,
        status: SchedulingStatus,
        maybeUserId: Option[String]
      ): IO[ScheduledVideoDownload] = notUsed

      override def getById(id: String, maybeUserId: Option[String]): IO[ScheduledVideoDownload] = notUsed

      override def updateWorkerStatus(workerStatus: WorkerStatus): IO[Unit] = notUsed

      override val getWorkerStatus: IO[WorkerStatus] = notUsed

      override def updateDownloadProgress(
        id: String,
        timestamp: Instant,
        downloadedBytes: Long
      ): IO[ScheduledVideoDownload] = notUsed

      override def deleteById(id: String, maybeUserId: Option[String]): IO[ScheduledVideoDownload] = notUsed
    }

  private def run(
    schedule: IO[ScheduledVideoResult],
    userExists: Boolean,
    received: ReceivedMessage
  ): IO[(List[MainToFallbackMessage], List[String])] =
    for {
      dao <- StubFallbackSyncDao(SyncedVideo(scheduledVideoDownload("video-1"), List("user-1")))
      transport <- RecordingTransport()
      queue <- Ref.of[IO, List[String]](Nil).map(new StubQueue(_))
      consumer =
        new FallbackRequestConsumer[IO, IO](queue, schedulingService(schedule), userDao(userExists), dao, transport)
      _ <- consumer.handle(received)
      sent <- transport.messages
      deleted <- queue.deletedHandles
    } yield (sent, deleted)

  private val scheduled = IO.pure(ScheduledVideoResult.NewlyScheduled(scheduledVideoDownload("video-1")))

  "FallbackRequestConsumer" should "reply Scheduled with the video's state and delete the message" in runIO {
    run(scheduled, userExists = true, message(body(request))).map {
      case (sent, deleted) =>
        sent match {
          case List(RequestResolved("request-1", "user-1", ResolutionOutcome.Scheduled(upsert))) =>
            upsert.videoId mustBe "video-1"
          case other => fail(s"Unexpected messages: $other")
        }
        deleted mustBe List("receipt-1")
    }
  }

  it should "reject an unknown user without scheduling" in runIO {
    run(IO.raiseError(new AssertionError("must not schedule")), userExists = false, message(body(request))).map {
      case (sent, deleted) =>
        sent mustBe List(RequestResolved("request-1", "user-1", ResolutionOutcome.Rejected("Unknown user: user-1")))
        deleted mustBe List("receipt-1")
    }
  }

  it should "reject a URL that cannot be parsed without scheduling" in runIO {
    val badUrl = request.copy(url = "https://exa mple.com/%zz")

    run(IO.raiseError(new AssertionError("must not schedule")), userExists = true, message(body(badUrl))).map {
      case (sent, deleted) =>
        sent.collect { case RequestResolved(_, _, ResolutionOutcome.Rejected(reason)) => reason } must have size 1
        deleted mustBe List("receipt-1")
    }
  }

  it should "reject permanent scheduling failures" in runIO {
    run(IO.raiseError(ValidationException("Unable infer video site")), userExists = true, message(body(request))).map {
      case (sent, deleted) =>
        sent mustBe List(
          RequestResolved("request-1", "user-1", ResolutionOutcome.Rejected("Unable infer video site"))
        )
        deleted mustBe List("receipt-1")
    }
  }

  it should "leave transient failures on the queue until the final attempt" in runIO {
    val transient = IO.raiseError(ExternalServiceException("metadata timeout"))

    for {
      early <- run(transient, userExists = true, message(body(request), receiveCount = 2))
      last <- run(transient, userExists = true, message(body(request), receiveCount = 5))
    } yield {
      early mustBe ((Nil, Nil))
      last._1.collect { case RequestResolved(_, _, ResolutionOutcome.Rejected(reason)) => reason }.head must
        include("after 5 attempts")
      last._2 mustBe List("receipt-1")
    }
  }

  it should "leave an undecodable message for the dead-letter queue" in runIO {
    run(scheduled, userExists = true, message("{not json")).map { _ mustBe ((Nil, Nil)) }
  }
}
