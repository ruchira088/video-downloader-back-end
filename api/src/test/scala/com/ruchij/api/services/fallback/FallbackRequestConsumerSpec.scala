package com.ruchij.api.services.fallback

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.kernel.Ref
import com.ruchij.api.daos.user.UserDao
import com.ruchij.api.daos.user.models.{Email, Role, User}
import com.ruchij.api.services.fallback.FallbackSyncStubs._
import com.ruchij.api.services.fallback.FallbackSyncTestData.{capturedAt, scheduledVideoDownload}
import com.ruchij.api.services.fallback.aws.{FallbackRequestQueue, FallbackSyncTransport, ReceivedMessage}
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

  /** Lower-level runner for scenarios that need a bespoke `UserDao`/`FallbackSyncDao`/`FallbackSyncTransport`
    * (e.g. one that fails), where the caller already holds the transport/dao it wants to inspect afterwards.
    * Errors from `handle` are swallowed (via `.attempt`) so the assertion can inspect the resulting state
    * (what was deleted, what if anything was sent) instead of the test itself failing. */
  private def runConsumer(
    schedule: IO[ScheduledVideoResult],
    userDao: UserDao[IO],
    dao: FallbackSyncDao[IO],
    transport: FallbackSyncTransport[IO],
    received: ReceivedMessage
  ): IO[List[String]] =
    for {
      queue <- Ref.of[IO, List[String]](Nil).map(new StubQueue(_))
      consumer = new FallbackRequestConsumer[IO, IO](queue, schedulingService(schedule), userDao, dao, transport)
      _ <- consumer.handle(received).attempt
      deleted <- queue.deletedHandles
    } yield deleted

  private def failingUserDao(error: Throwable): UserDao[IO] =
    new UserDao[IO] {
      override def insert(user: User): IO[Int] = notUsed
      override def findByEmail(email: Email): IO[Option[User]] = notUsed
      override def deleteById(userId: String): IO[Int] = notUsed
      override def findById(userId: String): IO[Option[User]] = IO.raiseError(error)
    }

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

  it should "leave a persistent user-lookup failure on the queue until the final attempt, then reject" in runIO {
    val dbUnavailable = new RuntimeException("User DB unavailable")

    for {
      dao <- StubFallbackSyncDao(SyncedVideo(scheduledVideoDownload("video-1"), List("user-1")))

      earlyTransport <- RecordingTransport()
      earlyDeleted <-
        runConsumer(scheduled, failingUserDao(dbUnavailable), dao, earlyTransport, message(body(request), 2))
      earlySent <- earlyTransport.messages

      finalTransport <- RecordingTransport()
      finalDeleted <-
        runConsumer(scheduled, failingUserDao(dbUnavailable), dao, finalTransport, message(body(request), 5))
      finalSent <- finalTransport.messages
    } yield {
      earlySent mustBe Nil
      earlyDeleted mustBe Nil

      finalSent.collect { case RequestResolved(_, _, ResolutionOutcome.Rejected(reason)) => reason }.head must
        include("after 5 attempts")
      finalDeleted mustBe List("receipt-1")
    }
  }

  it should "reject once retries are exhausted when the scheduled video can't be found for the reply" in runIO {
    for {
      emptyDao <- StubFallbackSyncDao()
      transport <- RecordingTransport()
      deleted <- runConsumer(scheduled, userDao(exists = true), emptyDao, transport, message(body(request), 5))
      sent <- transport.messages
    } yield {
      sent.collect { case RequestResolved(_, _, ResolutionOutcome.Rejected(reason)) => reason }.head must
        include("after 5 attempts")
      deleted mustBe List("receipt-1")
    }
  }

  it should "not delete the message when sending the reply fails" in runIO {
    val failingTransport =
      new FallbackSyncTransport[IO] {
        override def send(messages: List[MainToFallbackMessage]): IO[Unit] =
          IO.raiseError(new RuntimeException("SQS unavailable"))
      }

    for {
      dao <- StubFallbackSyncDao(SyncedVideo(scheduledVideoDownload("video-1"), List("user-1")))
      deleted <- runConsumer(scheduled, userDao(exists = true), dao, failingTransport, message(body(request)))
    } yield deleted mustBe Nil
  }

  it should "drain a batch and stop once the queue is empty, without one message's failure blocking the rest" in
    runIO {
      val secondRequest = request.copy(requestId = "request-2")
      val firstMessage = ReceivedMessage(body(request), "receipt-1", 1)
      val secondMessage = ReceivedMessage(body(secondRequest), "receipt-2", 1)

      final class ScriptedQueue(responses: Ref[IO, List[List[ReceivedMessage]]], deleted: Ref[IO, List[String]])
          extends FallbackRequestQueue[IO] {
        override val receive: IO[List[ReceivedMessage]] =
          responses.modify {
            case Nil => (Nil, Nil)
            case head :: tail => (tail, head)
          }
        override def delete(receiptHandle: String): IO[Unit] = deleted.update(_ :+ receiptHandle)
        val deletedHandles: IO[List[String]] = deleted.get
      }

      for {
        dao <- StubFallbackSyncDao(SyncedVideo(scheduledVideoDownload("video-1"), List("user-1")))
        // Fails sending the first message's reply (so its handling fails and it stays on the queue), then
        // succeeds for the second, proving one message's failure doesn't stop the batch or the drain loop.
        flaky <- FlakyTransport(1)
        responses <- Ref.of[IO, List[List[ReceivedMessage]]](List(List(firstMessage, secondMessage), Nil))
        deletedRef <- Ref.of[IO, List[String]](Nil)
        queue = new ScriptedQueue(responses, deletedRef)
        consumer =
          new FallbackRequestConsumer[IO, IO](queue, schedulingService(scheduled), userDao(exists = true), dao, flaky)
        _ <- consumer.drain
        remainingResponses <- responses.get
        deleted <- queue.deletedHandles
        sent <- flaky.recording.messages
      } yield {
        remainingResponses mustBe Nil
        deleted mustBe List("receipt-2")
        sent.collect { case RequestResolved(requestId, _, _) => requestId } mustBe List("request-2")
      }
    }
}
