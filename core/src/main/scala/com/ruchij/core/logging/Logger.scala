package com.ruchij.core.logging

import cats.effect.Sync
import com.typesafe.scalalogging.{Logger => TypesafeLogger}

import scala.reflect.ClassTag

final case class Logger(logger: TypesafeLogger) {
  def trace[F[_]: Sync](message: String): F[Unit] = Sync[F].delay(logger.trace(message))

  def debug[F[_]: Sync](message: String): F[Unit] = Sync[F].delay(logger.debug(message))

  def info[F[_]: Sync](message: String): F[Unit] = Sync[F].delay(logger.info(message))

  def warn[F[_]: Sync](message: String): F[Unit] = Sync[F].delay(logger.warn(message))

  def error[F[_]: Sync](message: String, throwable: Throwable): F[Unit] =
    Sync[F].delay {
      logger.error(message, throwable)
    }
}

object Logger {
  def apply[A: ClassTag]: Logger = Logger(TypesafeLogger[A])
}
