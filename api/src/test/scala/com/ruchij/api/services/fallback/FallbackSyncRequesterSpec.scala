package com.ruchij.api.services.fallback

import cats.effect.IO
import cats.effect.kernel.Ref
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

    new FallbackSyncRequester[IO](hangingPublisher, gracePeriod = 50.millis)
      .request("video-1")
      .timeout(5.seconds)
      .attempt
      .map(result => result mustBe Right(()))
  }

  it should "return within the grace period when the publisher's send is uncancelable and stuck" in {
    val test =
      new FallbackSyncRequester[IO](uncancelableHangingPublisher(1.minute), gracePeriod = 500.millis)
        .request("video-1")
        .timed

    runIO {
      TestControl.executeEmbed(test).map {
        case (duration, _) => duration mustBe 500.millis
      }
    }
  }

  it should "skip without waiting once every in-flight slot is held by a stuck publish" in {
    val test =
      for {
        attempts <- Ref.of[IO, Int](0)
        publisher = new Publisher[IO, FallbackSyncRequest] {
          override val publish: Pipe[IO, FallbackSyncRequest, Unit] = _.evalMap(publishOne)

          override def publishOne(input: FallbackSyncRequest): IO[Unit] =
            attempts.update(_ + 1) *> IO.uncancelable(_ => IO.sleep(1.minute))
        }
        requester = new FallbackSyncRequester[IO](publisher, gracePeriod = 500.millis, maxInFlight = 2)
        _ <- requester.request("video-1")
        _ <- requester.request("video-2")
        (duration, _) <- requester.request("video-3").timed
        attempted <- attempts.get
      } yield (duration, attempted)

    runIO {
      TestControl.executeEmbed(test).map {
        case (duration, attempted) =>
          duration mustBe Duration.Zero
          attempted mustBe 2
      }
    }
  }

  it should "free an in-flight slot once a stuck publish finally completes" in {
    val test =
      for {
        recorder <- RecordingPublisher[FallbackSyncRequest]
        stuckOnce <- Ref.of[IO, Boolean](true)
        publisher = new Publisher[IO, FallbackSyncRequest] {
          override val publish: Pipe[IO, FallbackSyncRequest, Unit] = _.evalMap(publishOne)

          override def publishOne(input: FallbackSyncRequest): IO[Unit] =
            stuckOnce.getAndSet(false).ifM(IO.uncancelable(_ => IO.sleep(10.seconds)), recorder.publishOne(input))
        }
        requester = new FallbackSyncRequester[IO](publisher, gracePeriod = 500.millis, maxInFlight = 1)
        _ <- requester.request("stuck")
        _ <- IO.sleep(20.seconds)
        _ <- requester.request("video-2")
        published <- recorder.messages
      } yield published

    runIO {
      TestControl.executeEmbed(test).map(published => published mustBe List(FallbackSyncRequest("video-2")))
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

  it should "publish the remaining ids in requestAll even when one id's publish raises" in runIO {
    for {
      recorded <- Ref.of[IO, List[String]](Nil)
      publisher = new Publisher[IO, FallbackSyncRequest] {
        override val publish: Pipe[IO, FallbackSyncRequest, Unit] = _.evalMap(publishOne)

        override def publishOne(input: FallbackSyncRequest): IO[Unit] =
          if (input.videoId == "a") IO.raiseError(new RuntimeException("boom for video a"))
          else recorded.update(_ :+ input.videoId)
      }
      result <- new FallbackSyncRequester[IO](publisher).requestAll(Seq("a", "b", "c")).attempt
      published <- recorded.get
    } yield {
      result mustBe Right(())
      published mustBe List("b", "c")
    }
  }

  it should "return from the whole fan-out within one grace period, not N times it" in {
    val test =
      new FallbackSyncRequester[IO](uncancelableHangingPublisher(1.minute), gracePeriod = 500.millis)
        .requestAll(Seq("video-1", "video-2", "video-3", "video-4"))
        .timed

    runIO {
      TestControl.executeEmbed(test).map {
        case (duration, _) => duration mustBe 500.millis
      }
    }
  }
}
