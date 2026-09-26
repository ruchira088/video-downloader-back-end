package com.ruchij.api.services.fallback

import cats.effect.IO
import cats.effect.testkit.TestControl
import com.ruchij.api.services.fallback.FallbackSyncStubs.{FailingPublisher, RecordingPublisher}
import com.ruchij.api.services.fallback.models.FallbackSyncRequest
import com.ruchij.core.messaging.Publisher
import com.ruchij.core.test.IOSupport.runIO
import fs2.Pipe
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.duration._

class FallbackSyncRequesterSpec extends AnyFlatSpec with Matchers {

  /** An fs2-kafka-style producer stuck on metadata: the send is masked (uncancelable), so a plain
    * `Async[F].timeout` cannot return until the send itself finishes. */
  private def uncancelableHangingPublisher(sleep: FiniteDuration): Publisher[IO, FallbackSyncRequest] =
    new Publisher[IO, FallbackSyncRequest] {
      override val publish: Pipe[IO, FallbackSyncRequest, Unit] = _.evalMap(publishOne)

      override def publishOne(input: FallbackSyncRequest): IO[Unit] = IO.uncancelable(_ => IO.sleep(sleep))
    }

  "FallbackSyncRequester" should "publish a sync request for the video" in runIO {
    for {
      publisher <- RecordingPublisher[FallbackSyncRequest]
      _ <- new FallbackSyncRequester[IO](publisher).request("video-1")
      published <- publisher.messages
    } yield published mustBe List(FallbackSyncRequest("video-1"))
  }

  it should "not fail when the publisher fails" in runIO {
    new FallbackSyncRequester[IO](new FailingPublisher[FallbackSyncRequest]).request("video-1").attempt.map {
      result => result mustBe Right(())
    }
  }

  it should "give up instead of blocking when the publisher hangs" in runIO {
    val hangingPublisher = new Publisher[IO, FallbackSyncRequest] {
      override val publish: Pipe[IO, FallbackSyncRequest, Unit] = _.evalMap(publishOne)

      override def publishOne(input: FallbackSyncRequest): IO[Unit] = IO.never
    }

    new FallbackSyncRequester[IO](hangingPublisher, timeout = 50.millis)
      .request("video-1")
      .timeout(5.seconds)
      .attempt
      .map(result => result mustBe Right(()))
  }

  it should "return promptly when the publisher's send is uncancelable and stuck, instead of waiting on it" in {
    val test =
      new FallbackSyncRequester[IO](uncancelableHangingPublisher(1.minute), timeout = 5.seconds)
        .request("video-1")
        .timed

    runIO {
      TestControl.executeEmbed(test).map {
        case (duration, _) => duration must be < 6.seconds
      }
    }
  }

  it should "publish every id in requestAll, one overall timeout regardless of count" in runIO {
    for {
      publisher <- RecordingPublisher[FallbackSyncRequest]
      _ <- new FallbackSyncRequester[IO](publisher).requestAll(Seq("video-1", "video-2", "video-3"))
      published <- publisher.messages
    } yield {
      published mustBe List(
        FallbackSyncRequest("video-1"),
        FallbackSyncRequest("video-2"),
        FallbackSyncRequest("video-3")
      )
    }
  }

  it should "give up on the whole fan-out within about one timeout, not N times the timeout" in {
    val test =
      new FallbackSyncRequester[IO](uncancelableHangingPublisher(1.minute), timeout = 5.seconds)
        .requestAll(Seq("video-1", "video-2", "video-3", "video-4"))
        .timed

    runIO {
      TestControl.executeEmbed(test).map {
        case (duration, _) => duration must be < 6.seconds
      }
    }
  }
}
