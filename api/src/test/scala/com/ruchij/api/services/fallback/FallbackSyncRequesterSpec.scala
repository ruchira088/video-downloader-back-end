package com.ruchij.api.services.fallback

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.testkit.TestControl
import cats.implicits._
import com.ruchij.api.services.fallback.FallbackSyncStubs.{FailingPublisher, RecordingPublisher}
import com.ruchij.api.services.fallback.models.FallbackSyncRequest
import com.ruchij.core.messaging.Publisher
import com.ruchij.core.test.IOSupport.runIO
import fs2.Pipe
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.duration._

class FallbackSyncRequesterSpec extends AnyFlatSpec with Matchers {

  private def publisherOf(send: FallbackSyncRequest => IO[Unit]): Publisher[IO, FallbackSyncRequest] =
    new Publisher[IO, FallbackSyncRequest] {
      override val publish: Pipe[IO, FallbackSyncRequest, Unit] = _.evalMap(publishOne)

      override def publishOne(input: FallbackSyncRequest): IO[Unit] = send(input)
    }

  /** An fs2-kafka-style producer stuck on metadata: the send is masked (uncancelable), so a plain
    * `Async[F].timeout` cannot return until the send itself finishes. */
  private def uncancelableHangingPublisher(sleep: FiniteDuration): Publisher[IO, FallbackSyncRequest] =
    publisherOf(_ => IO.uncancelable(_ => IO.sleep(sleep)))

  private val cancelableHangingPublisher: Publisher[IO, FallbackSyncRequest] = publisherOf(_ => IO.never)

  private def requester(
    publisher: Publisher[IO, FallbackSyncRequest],
    onDropped: IO[Unit] = IO.unit,
    gracePeriod: FiniteDuration = 500.millis,
    publishTimeout: FiniteDuration = 30.seconds,
    maxInFlight: Int = 32
  ): PublishingFallbackSyncRequester[IO] =
    new PublishingFallbackSyncRequester[IO](publisher, onDropped, gracePeriod, publishTimeout, maxInFlight)

  // Tests that read what was published after a call returns run under TestControl, whose virtual clock only moves
  // once every fiber is blocked, so the background publish always finishes within the call's grace wait; on the
  // real runtime a loaded CI machine could let the 500 ms grace period elapse first.

  "PublishingFallbackSyncRequester" should "publish a sync request for the video" in runIO {
    val test =
      for {
        publisher <- RecordingPublisher[FallbackSyncRequest]
        _ <- requester(publisher).request("video-1")
        published <- publisher.messages
      } yield published

    TestControl.executeEmbed(test).map(published => published mustBe List(FallbackSyncRequest("video-1")))
  }

  it should "not fail when the publisher fails" in runIO {
    requester(new FailingPublisher[FallbackSyncRequest]).request("video-1").attempt.map { result =>
      result mustBe Right(())
    }
  }

  it should "give up instead of blocking when the publisher hangs" in runIO {
    TestControl
      .executeEmbed(requester(cancelableHangingPublisher, gracePeriod = 50.millis).request("video-1").timed)
      .map { case (duration, _) => duration mustBe 50.millis }
  }

  it should "return within the grace period when the publisher's send is uncancelable and stuck" in {
    val test = requester(uncancelableHangingPublisher(1.minute)).request("video-1").timed

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
        publisher = publisherOf(_ => attempts.update(_ + 1) *> IO.uncancelable(_ => IO.sleep(1.minute)))
        stuck = requester(publisher, maxInFlight = 2)
        _ <- stuck.request("video-1")
        _ <- stuck.request("video-2")
        (duration, _) <- stuck.request("video-3").timed
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
        publisher = publisherOf { input =>
          stuckOnce.getAndSet(false).ifM(IO.uncancelable(_ => IO.sleep(10.seconds)), recorder.publishOne(input))
        }
        stuck = requester(publisher, maxInFlight = 1)
        _ <- stuck.request("stuck")
        _ <- IO.sleep(20.seconds)
        _ <- stuck.request("video-2")
        published <- recorder.messages
      } yield published

    runIO {
      TestControl.executeEmbed(test).map(published => published mustBe List(FallbackSyncRequest("video-2")))
    }
  }

  it should "release a slot exactly once and still publish when the caller is cancelled during the grace wait" in {
    val test =
      for {
        recorder <- RecordingPublisher[FallbackSyncRequest]
        slow = requester(publisherOf(input => IO.sleep(2.seconds) *> recorder.publishOne(input)), maxInFlight = 1)
        caller <- slow.request("video-1").start
        _ <- IO.sleep(100.millis)
        _ <- caller.cancel
        whileRunning <- slow.inFlightCount
        _ <- IO.sleep(3.seconds)
        afterwards <- slow.inFlightCount
        _ <- slow.request("video-2")
        _ <- IO.sleep(3.seconds)
        finalCount <- slow.inFlightCount
        published <- recorder.messages
      } yield (whileRunning, afterwards, finalCount, published)

    runIO {
      TestControl.executeEmbed(test).map {
        case (whileRunning, afterwards, finalCount, published) =>
          whileRunning mustBe 1
          afterwards mustBe 0
          finalCount mustBe 0
          published mustBe List(FallbackSyncRequest("video-1"), FallbackSyncRequest("video-2"))
      }
    }
  }

  it should "release the slot of a cancelable hang once publishTimeout cancels it" in {
    val test =
      for {
        hanging <- IO.pure(requester(cancelableHangingPublisher, publishTimeout = 1.second, maxInFlight = 1))
        _ <- hanging.request("video-1")
        during <- hanging.inFlightCount
        _ <- IO.sleep(2.seconds)
        after <- hanging.inFlightCount
      } yield (during, after)

    runIO(TestControl.executeEmbed(test).map(_ mustBe ((1, 0))))
  }

  it should "hold the slot of an uncancelable hang until the send finishes, past publishTimeout" in {
    val test =
      for {
        hanging <- IO.pure {
          requester(uncancelableHangingPublisher(10.seconds), publishTimeout = 1.second, maxInFlight = 1)
        }
        _ <- hanging.request("video-1")
        _ <- IO.sleep(5.seconds)
        pastTimeout <- hanging.inFlightCount
        _ <- IO.sleep(6.seconds)
        afterSend <- hanging.inFlightCount
      } yield (pastTimeout, afterSend)

    runIO(TestControl.executeEmbed(test).map(_ mustBe ((1, 0))))
  }

  it should "flag a reconcile for a failed or timed-out publish, a skip at capacity and a partial requestAll" in {
    def flagsFor(
      run: PublishingFallbackSyncRequester[IO] => IO[Unit],
      publisher: Publisher[IO, FallbackSyncRequest],
      publishTimeout: FiniteDuration = 1.second
    ): IO[Int] =
      for {
        flags <- Ref.of[IO, Int](0)
        subject =
          requester(publisher, onDropped = flags.update(_ + 1), publishTimeout = publishTimeout, maxInFlight = 1)
        _ <- run(subject)
        _ <- IO.sleep(5.seconds)
        flagged <- flags.get
      } yield flagged

    val partiallyFailing =
      publisherOf(input => IO.raiseError(new RuntimeException("boom")).whenA(input.videoId == "b"))

    val test =
      for {
        failed <- flagsFor(_.request("video-1"), new FailingPublisher[FallbackSyncRequest])
        timedOut <- flagsFor(_.request("video-1"), cancelableHangingPublisher)
        // The first request holds the only slot until its send finishes, 4 s later, so the second one is skipped
        atCapacity <- flagsFor(
          subject => subject.request("video-1") *> subject.request("video-2"),
          uncancelableHangingPublisher(4.seconds),
          publishTimeout = 30.seconds
        )
        partial <- flagsFor(_.requestAll(List("a", "b", "c")), partiallyFailing)
        healthy <- RecordingPublisher[FallbackSyncRequest].flatMap(publisher => flagsFor(_.request("ok"), publisher))
      } yield (failed, timedOut, atCapacity, partial, healthy)

    runIO(TestControl.executeEmbed(test).map(_ mustBe ((1, 1, 1, 1, 0))))
  }

  it should "run at most one reconcile flag at a time however many requests are dropped" in {
    val test =
      for {
        running <- Ref.of[IO, Int](0)
        maxRunning <- Ref.of[IO, Int](0)
        runs <- Ref.of[IO, Int](0)
        onDropped = running.updateAndGet(_ + 1).flatMap(now => maxRunning.update(_.max(now))) *>
          IO.sleep(1.second) *> runs.update(_ + 1) *> running.update(_ - 1)
        subject = requester(uncancelableHangingPublisher(1.minute), onDropped = onDropped, maxInFlight = 1)
        _ <- subject.request("stuck")
        _ <- (1 to 100).toList.traverse_(index => subject.request(s"video-$index"))
        _ <- IO.sleep(10.seconds)
        maxConcurrent <- maxRunning.get
        completedRuns <- runs.get
      } yield (maxConcurrent, completedRuns)

    runIO {
      TestControl.executeEmbed(test).map {
        case (maxConcurrent, completedRuns) =>
          maxConcurrent mustBe 1
          // One run for the first drop, plus one more covering every drop made while it ran
          completedRuns mustBe 2
      }
    }
  }

  it should "skip publishing for the degraded period after a publish times out, then resume" in {
    val test =
      for {
        attempts <- Ref.of[IO, Int](0)
        flags <- Ref.of[IO, Int](0)
        subject = requester(
          publisherOf(_ => attempts.update(_ + 1) *> IO.never),
          onDropped = flags.update(_ + 1),
          publishTimeout = 1.second
        )
        _ <- subject.request("video-1")
        _ <- IO.sleep(2.seconds)
        (degradedDuration, _) <- subject.request("video-2").timed
        _ <- subject.request("video-3")
        attemptsWhileDegraded <- attempts.get
        _ <- IO.sleep(1.second)
        flagsWhileDegraded <- flags.get
        _ <- IO.sleep(60.seconds)
        _ <- subject.request("video-4")
        attemptsAfterwards <- attempts.get
      } yield (degradedDuration, attemptsWhileDegraded, flagsWhileDegraded, attemptsAfterwards)

    runIO {
      TestControl.executeEmbed(test).map {
        case (degradedDuration, attemptsWhileDegraded, flagsWhileDegraded, attemptsAfterwards) =>
          degradedDuration mustBe Duration.Zero
          attemptsWhileDegraded mustBe 1
          // The timeout's own flag plus the skipped calls' flags; at least one reconcile is flagged for the skips
          flagsWhileDegraded must be >= 2
          attemptsAfterwards mustBe 2
      }
    }
  }

  it should "open the breaker at publishTimeout even while an uncancelable send keeps running" in {
    val test =
      for {
        attempts <- Ref.of[IO, Int](0)
        subject = requester(
          publisherOf(_ => attempts.update(_ + 1) *> IO.uncancelable(_ => IO.sleep(50.seconds))),
          publishTimeout = 1.second
        )
        _ <- subject.request("video-1")
        _ <- IO.sleep(2.seconds)
        (duration, _) <- subject.request("video-2").timed
        attempted <- attempts.get
      } yield (duration, attempted)

    runIO(TestControl.executeEmbed(test).map(_ mustBe ((Duration.Zero, 1))))
  }

  it should "publish every id in requestAll" in runIO {
    val test =
      for {
        publisher <- RecordingPublisher[FallbackSyncRequest]
        _ <- requester(publisher).requestAll(Seq("video-1", "video-2", "video-3"))
        published <- publisher.messages
      } yield published

    TestControl.executeEmbed(test).map { published =>
      published mustBe List(
        FallbackSyncRequest("video-1"),
        FallbackSyncRequest("video-2"),
        FallbackSyncRequest("video-3")
      )
    }
  }

  it should "publish the remaining ids in requestAll even when one id's publish raises" in runIO {
    val test =
      for {
        recorded <- Ref.of[IO, List[String]](Nil)
        publisher = publisherOf { input =>
          if (input.videoId == "a") IO.raiseError(new RuntimeException("boom for video a"))
          else recorded.update(_ :+ input.videoId)
        }
        result <- requester(publisher).requestAll(Seq("a", "b", "c")).attempt
        published <- recorded.get
      } yield (result, published)

    TestControl.executeEmbed(test).map {
      case (result, published) =>
        result mustBe Right(())
        published mustBe List("b", "c")
    }
  }

  it should "return from the whole fan-out within one grace period, not N times it" in {
    val test =
      requester(uncancelableHangingPublisher(1.minute))
        .requestAll(Seq("video-1", "video-2", "video-3", "video-4"))
        .timed

    runIO {
      TestControl.executeEmbed(test).map {
        case (duration, _) => duration mustBe 500.millis
      }
    }
  }

  "FallbackSyncRequester.noOp" should "return at once without publishing anything" in {
    runIO {
      TestControl.executeEmbed(FallbackSyncRequester.noOp[IO].requestAll(List("video-1")).timed).map {
        case (duration, _) => duration mustBe Duration.Zero
      }
    }
  }
}
