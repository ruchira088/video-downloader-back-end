package com.ruchij.core.utils

import cats.effect.testkit.TestControl
import cats.effect.{IO, Ref}
import com.ruchij.core.test.IOSupport.runIO
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.util.concurrent.TimeoutException
import scala.concurrent.duration._

class TimersSpec extends AnyFlatSpec with Matchers {
  // Timers polls the reset signal every 5 seconds, so intervals must be longer than that for a reset to be seen
  private val Interval: FiniteDuration = 12.seconds

  private def timeTimer(resetSignal: Ref[IO, Boolean]): IO[(Either[Throwable, Unit], FiniteDuration)] =
    for {
      start <- IO.monotonic
      result <- Timers.createResettableTimer[IO](Interval, resetSignal)
      end <- IO.monotonic
    } yield result -> (end - start)

  "createResettableTimer" should "time out after the interval when the signal is never set" in runIO {
    TestControl.executeEmbed {
      for {
        resetSignal <- Ref.of[IO, Boolean](false)
        (result, elapsed) <- timeTimer(resetSignal)
      } yield {
        elapsed mustBe Interval
        result.left.toOption.get mustBe a[TimeoutException]
        result.left.toOption.get.getMessage must include(Interval.toMillis.toString)
      }
    }
  }

  it should "restart the interval from the poll that observes a reset" in runIO {
    TestControl.executeEmbed {
      for {
        resetSignal <- Ref.of[IO, Boolean](false)
        fiber <- IO.sleep(3.seconds).productR(resetSignal.set(true)).start
        (result, elapsed) <- timeTimer(resetSignal)
        _ <- fiber.join
        signalAfter <- resetSignal.get
      } yield {
        // The reset set at 3s is observed by the poll at 5s, which restarts the 12s interval
        elapsed mustBe 5.seconds + Interval
        signalAfter mustBe false
        result.left.toOption.get mustBe a[TimeoutException]
      }
    }
  }

  it should "restart the interval for every reset it observes" in runIO {
    TestControl.executeEmbed {
      for {
        resetSignal <- Ref.of[IO, Boolean](false)
        fiber <- IO.sleep(3.seconds).productR(resetSignal.set(true)).productR {
          IO.sleep(5.seconds).productR(resetSignal.set(true))
        }.start
        (result, elapsed) <- timeTimer(resetSignal)
        _ <- fiber.join
      } yield {
        // Resets set at 3s and 8s are observed by the polls at 5s and 10s
        elapsed mustBe 10.seconds + Interval
        result.left.toOption.get mustBe a[TimeoutException]
      }
    }
  }

  it should "treat a signal that is already set as a reset" in runIO {
    TestControl.executeEmbed {
      for {
        resetSignal <- Ref.of[IO, Boolean](true)
        (result, elapsed) <- timeTimer(resetSignal)
        signalAfter <- resetSignal.get
      } yield {
        elapsed mustBe 5.seconds + Interval
        signalAfter mustBe false
        result.left.toOption.get mustBe a[TimeoutException]
      }
    }
  }

  it should "ignore a reset set after the interval has already elapsed" in runIO {
    TestControl.executeEmbed {
      for {
        resetSignal <- Ref.of[IO, Boolean](false)
        fiber <- IO.sleep(Interval + 1.second).productR(resetSignal.set(true)).start
        (result, elapsed) <- timeTimer(resetSignal)
        _ <- fiber.join
      } yield {
        elapsed mustBe Interval
        result.left.toOption.get mustBe a[TimeoutException]
      }
    }
  }
}
