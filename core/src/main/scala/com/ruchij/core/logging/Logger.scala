package com.ruchij.core.logging

import cats.effect.Sync
import com.typesafe.scalalogging.{Logger => TypesafeLogger}

import scala.reflect.ClassTag

case class Logger[A](logger: TypesafeLogger) {
  def debug[F[_]: Sync](message: String): F[Unit] = Sync[F].delay(logger.debug(message))

  def info[F[_]: Sync](message: String): F[Unit] = Sync[F].delay(logger.info(message))

  def warn[F[_]: Sync](message: String): F[Unit] = Sync[F].delay(logger.warn(message))

  def error[F[_]: Sync](message: String, throwable: Throwable): F[Unit] =
    Sync[F].delay {
      logger.error(message, throwable)
    }
}

object Logger {
  def apply[A: ClassTag]: Logger[A] = Logger[A](TypesafeLogger[A])
}
