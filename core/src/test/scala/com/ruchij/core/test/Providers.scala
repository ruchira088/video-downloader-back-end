package com.ruchij.core.test

import cats.effect.{Blocker, Clock, ContextShift, IO, Sync, Timer}
import org.joda.time.DateTime

import java.util.concurrent.TimeUnit
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

object Providers {
  def stubClock[F[_]: Sync](dateTime: => DateTime): Clock[F] = new Clock[F] {
    override def realTime(unit: TimeUnit): F[Long] =
      Sync[F].delay(unit.convert(dateTime.getMillis, TimeUnit.MILLISECONDS))

    override def monotonic(unit: TimeUnit): F[Long] = realTime(unit)
  }

  def blocker(implicit executionContext: ExecutionContext): Blocker =
    Blocker.liftExecutionContext(executionContext)

  implicit def contextShift(implicit executionContext: ExecutionContext): ContextShift[IO] =
    IO.contextShift(executionContext)

  implicit def timer(implicit executionContext: ExecutionContext): Timer[IO] =
    IO.timer(executionContext)

  def stubTimer(dateTime: => DateTime)(implicit executionContext: ExecutionContext): Timer[IO] =
    new Timer[IO] {
      override def clock: Clock[IO] = stubClock(dateTime)

      override def sleep(duration: FiniteDuration): IO[Unit] = timer.sleep(duration)
    }
}
