package com.ruchij.core.types

import cats.effect.IO
import com.ruchij.core.test.IOSupport.runIO
import org.joda.time.DateTime
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class JodaClockSpec extends AnyFlatSpec with Matchers {

  "JodaClock" should "return current time using implicit instance" in runIO {
    JodaClock[IO].timestamp.map { timestamp =>
      timestamp mustBe a[DateTime]
    }
  }

  it should "return different timestamps on successive calls" in runIO {
    for {
      timestamp1 <- JodaClock[IO].timestamp
      _ <- IO.sleep(scala.concurrent.duration.DurationInt(10).millis)
      timestamp2 <- JodaClock[IO].timestamp
    } yield {
      timestamp2.isAfter(timestamp1) || timestamp2.isEqual(timestamp1) mustBe true
    }
  }

  "JodaClock[F]" should "be summonable via apply" in runIO {
    JodaClock[IO].timestamp.map { timestamp =>
      timestamp mustBe a[DateTime]
    }
  }

  it should "work with custom implementation" in runIO {
    val fixedTime = new DateTime(2024, 6, 15, 10, 30, 0, 0)

    implicit val fixedClock: JodaClock[IO] = new JodaClock[IO] {
      override val timestamp: IO[DateTime] = IO.pure(fixedTime)
    }

    fixedClock.timestamp.map { timestamp =>
      timestamp mustBe fixedTime
    }
  }
}
