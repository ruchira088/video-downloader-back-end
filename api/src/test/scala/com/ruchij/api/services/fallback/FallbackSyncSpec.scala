package com.ruchij.api.services.fallback

import cats.effect.IO
import cats.effect.kernel.Ref
import com.ruchij.core.test.IOSupport.runIO
import fs2.Stream
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.duration._

class FallbackSyncSpec extends AnyFlatSpec with Matchers {

  /** Guards the ruling in the plan: `FallbackSync.stream` runs in a started fiber that nobody joins, so a
    * component stream that ends by raising must be restarted rather than allowed to end the whole
    * `parJoinUnbounded` stream (which would silently stop every other component too). */
  "resilient" should "restart a component stream that fails, instead of letting the failure end it" in runIO {
    for {
      attempts <- Ref.of[IO, Int](0)
      component = Stream.eval(attempts.updateAndGet(_ + 1)).flatMap { attempt =>
        if (attempt == 1) Stream.raiseError[IO](new RuntimeException("boom")) else Stream.emit(attempt)
      }
      values <- FallbackSync
        .resilient[IO, Int]("test-component", restartDelay = 10.millis)(component)
        .take(1)
        .compile
        .toList
      finalAttempts <- attempts.get
    } yield {
      values mustBe List(2)
      finalAttempts mustBe 2
    }
  }

  it should "keep restarting through repeated failures until the component succeeds" in runIO {
    for {
      attempts <- Ref.of[IO, Int](0)
      component = Stream.eval(attempts.updateAndGet(_ + 1)).flatMap { attempt =>
        if (attempt < 3) Stream.raiseError[IO](new RuntimeException("boom")) else Stream.emit(attempt)
      }
      values <- FallbackSync
        .resilient[IO, Int]("test-component", restartDelay = 10.millis)(component)
        .take(1)
        .compile
        .toList
    } yield values mustBe List(3)
  }

  it should "pass values through unchanged when the component never fails" in runIO {
    val component = Stream.emits(List(1, 2, 3)).covary[IO]

    FallbackSync.resilient[IO, Int]("test-component", restartDelay = 10.millis)(component).compile.toList.map {
      values => values mustBe List(1, 2, 3)
    }
  }
}
