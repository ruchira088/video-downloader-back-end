package com.ruchij.batch.health

import cats.effect.{Async, Ref, Resource}
import cats.effect.syntax.all._
import cats.implicits._
import fs2.Stream
import org.http4s.{HttpApp, HttpRoutes}
import org.http4s.dsl.Http4sDsl

import scala.concurrent.duration._

final class BatchHealth[F[_]: Async] private (state: Ref[F, BatchHealth.State]) {
  import BatchHealth._

  def running: Resource[F, Unit] =
    Resource.make(state.update(_.copy(started = true, running = true)))(_ =>
      state.update(_.copy(running = false, dependencyHealthy = false))
    )

  // Cache bounded checks so probes never wait for a dependency connection. A stale result fails closed.
  def monitor(databaseCheck: F[Unit], messagingCheck: F[Unit]): Stream[F, Unit] =
    monitor(databaseCheck.productR(messagingCheck))

  def monitor(check: F[Unit]): Stream[F, Unit] =
    Stream.repeatEval {
      check.timeout(2.seconds).attempt.flatMap { result =>
        Async[F].monotonic.flatMap { now =>
          state.update(_.copy(dependencyHealthy = result.isRight, checkedAt = Some(now)))
        }
      }
    }.metered(5.seconds)

  val routes: HttpApp[F] = {
    val dsl = new Http4sDsl[F] {}
    import dsl._

    def respond(predicate: State => Boolean) =
      state.get.flatMap(value => if (predicate(value)) Ok() else ServiceUnavailable())

    HttpRoutes.of[F] {
      case GET -> Root / "health" / "started" => respond(_.started)
      case GET -> Root / "health" / "live" => respond(_.running)
      case GET -> Root / "health" / "ready" =>
        Async[F].monotonic.flatMap { now =>
          respond(s => s.running && s.dependencyHealthy && s.checkedAt.exists(now - _ < 15.seconds))
        }
    }.orNotFound
  }
}

object BatchHealth {
  private final case class State(
    started: Boolean = false,
    running: Boolean = false,
    dependencyHealthy: Boolean = false,
    checkedAt: Option[FiniteDuration] = None
  )

  def create[F[_]: Async]: F[BatchHealth[F]] = Ref.of[F, State](State()).map(new BatchHealth(_))
}
