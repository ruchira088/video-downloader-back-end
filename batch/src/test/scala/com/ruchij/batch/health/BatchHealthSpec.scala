package com.ruchij.batch.health

import cats.effect.IO
import cats.effect.testkit.TestControl
import com.ruchij.core.test.IOSupport.runIO
import org.http4s.{Method, Request, Status, Uri}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.duration._

class BatchHealthSpec extends AnyFlatSpec with Matchers {
  private def status(health: BatchHealth[IO], endpoint: String): IO[Status] =
    health.routes.run(Request[IO](Method.GET, Uri.unsafeFromString(s"/health/$endpoint"))).map(_.status)

  "Batch health" should "track startup, scheduler lifetime and shutdown" in runIO {
    for {
      health <- BatchHealth.create[IO]
      _ <- status(health, "started").map(_ mustBe Status.ServiceUnavailable)
      _ <- status(health, "live").map(_ mustBe Status.ServiceUnavailable)
      _ <- health.running.use { _ =>
        status(health, "started").map(_ mustBe Status.Ok) *>
          status(health, "live").map(_ mustBe Status.Ok) *>
          status(health, "ready").map(_ mustBe Status.ServiceUnavailable)
      }
      _ <- status(health, "live").map(_ mustBe Status.ServiceUnavailable)
      _ <- status(health, "ready").map(_ mustBe Status.ServiceUnavailable)
    } yield ()
  }

  it should "fail readiness on dependency failure and recover without failing liveness" in runIO {
    TestControl.executeEmbed {
      for {
        health <- BatchHealth.create[IO]
        healthy <- cats.effect.Ref.of[IO, Boolean](true)
        check = healthy.get.flatMap(ok => if (ok) IO.unit else IO.raiseError(new Exception("offline")))
        _ <- health.running.use { _ =>
          health.monitor(check, IO.unit).compile.drain.background.use { _ =>
            for {
              _ <- IO.sleep(6.seconds)
              _ <- status(health, "ready").map(_ mustBe Status.Ok)
              _ <- healthy.set(false)
              _ <- IO.sleep(6.seconds)
              _ <- status(health, "ready").map(_ mustBe Status.ServiceUnavailable)
              _ <- status(health, "live").map(_ mustBe Status.Ok)
              _ <- healthy.set(true)
              _ <- IO.sleep(6.seconds)
              _ <- status(health, "ready").map(_ mustBe Status.Ok)
            } yield ()
          }
        }
      } yield ()
    }
  }

  it should "bound hung checks and expire cached successes" in runIO {
    TestControl.executeEmbed {
      for {
        health <- BatchHealth.create[IO]
        _ <- health.running.use { _ =>
          for {
            _ <- health.monitor(IO.unit).take(1).compile.drain
            _ <- status(health, "ready").map(_ mustBe Status.Ok)
            _ <- IO.sleep(16.seconds)
            _ <- status(health, "ready").map(_ mustBe Status.ServiceUnavailable)
            _ <- health.monitor(IO.never).take(1).compile.drain
            _ <- status(health, "ready").map(_ mustBe Status.ServiceUnavailable)
            _ <- status(health, "live").map(_ mustBe Status.Ok)
          } yield ()
        }
      } yield ()
    }
  }

  it should "require both database and messaging connectivity and recover after messaging reconnects" in runIO {
    TestControl.executeEmbed {
      for {
        health <- BatchHealth.create[IO]
        messagingHealthy <- cats.effect.Ref.of[IO, Boolean](true)
        messagingCheck = messagingHealthy.get.flatMap { healthy =>
          if (healthy) IO.unit else IO.raiseError(new Exception("messaging offline"))
        }
        _ <- health.running.use { _ =>
          health.monitor(IO.unit, messagingCheck).compile.drain.background.use { _ =>
            for {
              _ <- IO.sleep(6.seconds)
              _ <- status(health, "ready").map(_ mustBe Status.Ok)
              _ <- messagingHealthy.set(false)
              _ <- IO.sleep(6.seconds)
              _ <- status(health, "ready").map(_ mustBe Status.ServiceUnavailable)
              _ <- status(health, "live").map(_ mustBe Status.Ok)
              _ <- status(health, "started").map(_ mustBe Status.Ok)
              _ <- messagingHealthy.set(true)
              _ <- IO.sleep(6.seconds)
              _ <- status(health, "ready").map(_ mustBe Status.Ok)
            } yield ()
          }
        }
      } yield ()
    }
  }

  it should "time out a hung messaging check even when the database is healthy" in runIO {
    TestControl.executeEmbed {
      for {
        health <- BatchHealth.create[IO]
        _ <- health.running.use { _ =>
          health.monitor(IO.unit, IO.never).take(1).compile.drain *>
            status(health, "ready").map(_ mustBe Status.ServiceUnavailable) *>
            status(health, "live").map(_ mustBe Status.Ok)
        }
      } yield ()
    }
  }
}
