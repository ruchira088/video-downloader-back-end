package com.ruchij.test.utils

import java.util.concurrent.TimeUnit

import cats.effect.{Async, Blocker, Clock, ContextShift, IO, Sync, Timer}
import cats.implicits._
import com.ruchij.config.DatabaseConfiguration
import com.ruchij.daos.doobie.DoobieTransactor
import com.ruchij.migration.MigrationApp
import doobie.util.transactor.Transactor
import org.joda.time.DateTime

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

object Providers {
  val h2DatabaseConfiguration: DatabaseConfiguration =
    DatabaseConfiguration(
      "jdbc:h2:mem:weight-tracker;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
      "",
      ""
    )

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

  def h2Transactor[F[_]: Async: ContextShift](implicit executionContext: ExecutionContext): F[Transactor.Aux[F, Unit]] =
    MigrationApp
      .migration(h2DatabaseConfiguration, blocker)
      .productR(DoobieTransactor.create[F](h2DatabaseConfiguration, blocker))
}
