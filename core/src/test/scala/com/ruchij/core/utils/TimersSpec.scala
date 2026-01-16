package com.ruchij.core.utils

import cats.effect.{IO, Ref}
import com.ruchij.core.test.IOSupport.runIO
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.util.concurrent.TimeoutException
import scala.concurrent.duration._

class TimersSpec extends AnyFlatSpec with Matchers {

  "createResettableTimer" should "return timeout error when signal is never set" in runIO {
    for {
      resetSignal <- Ref.of[IO, Boolean](false)
      result <- Timers.createResettableTimer[IO](100.millis, resetSignal)
    } yield {
      result.isLeft mustBe true
      result.left.toOption.get mustBe a[TimeoutException]
      result.left.toOption.get.getMessage must include("100")
    }
  }

  it should "reset and continue when signal is set to true" in runIO {
    for {
      resetSignal <- Ref.of[IO, Boolean](false)

      // Set the reset signal to true after a short delay, then race with timer
      fiber <- IO.sleep(50.millis).flatMap(_ => resetSignal.set(true)).start

      // The timer should reset when it sees true, then eventually timeout
      result <- Timers.createResettableTimer[IO](200.millis, resetSignal)

      _ <- fiber.join
    } yield {
      result.isLeft mustBe true
      result.left.toOption.get mustBe a[TimeoutException]
    }
  }

  it should "include interval in timeout message" in runIO {
    for {
      resetSignal <- Ref.of[IO, Boolean](false)
      result <- Timers.createResettableTimer[IO](150.millis, resetSignal)
    } yield {
      result.isLeft mustBe true
      val timeoutException = result.left.toOption.get.asInstanceOf[TimeoutException]
      timeoutException.getMessage must include("150")
      timeoutException.getMessage must include("Timeout occurred")
      timeoutException.getMessage must include("resettable timer")
    }
  }

  it should "eventually timeout after resetting" in runIO {
    for {
      resetSignal <- Ref.of[IO, Boolean](true)

      // Start the timer - it should reset once when it sees true, then timeout
      result <- Timers.createResettableTimer[IO](150.millis, resetSignal)
    } yield {
      // After initial reset, timer should eventually timeout
      result.isLeft mustBe true
      result.left.toOption.get mustBe a[TimeoutException]
    }
  }
}
