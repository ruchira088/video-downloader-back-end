package com.ruchij.core.types

import cats.effect.IO
import com.ruchij.core.test.IOSupport.runIO
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.time.Instant

class ClockSpec extends AnyFlatSpec with Matchers {

  "Clock" should "return current time using implicit instance" in runIO {
    Clock[IO].timestamp.map { timestamp =>
      timestamp mustBe a[Instant]
    }
  }

  it should "return different timestamps on successive calls" in runIO {
    for {
      timestamp1 <- Clock[IO].timestamp
      _ <- IO.sleep(scala.concurrent.duration.DurationInt(10).millis)
      timestamp2 <- Clock[IO].timestamp
    } yield {
      (timestamp2.isAfter(timestamp1) || timestamp2 == timestamp1) mustBe true
    }
  }

  "Clock[F]" should "be summonable via apply" in runIO {
    Clock[IO].timestamp.map { timestamp =>
      timestamp mustBe a[Instant]
    }
  }

  it should "work with custom implementation" in runIO {
    val fixedTime = TimeUtils.instantOf(2024, 6, 15, 10, 30)

    implicit val fixedClock: Clock[IO] = new Clock[IO] {
      override val timestamp: IO[Instant] = IO.pure(fixedTime)
    }

    fixedClock.timestamp.map { timestamp =>
      timestamp mustBe fixedTime
    }
  }
}
