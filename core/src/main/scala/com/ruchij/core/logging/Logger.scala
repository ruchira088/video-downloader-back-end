package com.ruchij.core.logging

import cats.effect.Sync
import cats.implicits._
import com.typesafe.scalalogging.{Logger => TypesafeLogger}

import scala.reflect.ClassTag

case class Logger[F[_]: Sync](logger: TypesafeLogger) {
  def infoF(message: String): F[Unit] = Sync[F].delay(logger.info(message))

  def warnF(message: String): F[Unit] = Sync[F].delay(logger.warn(message))

  def errorF(message: String, throwable: Throwable): F[Unit] =
    Sync[F].delay(throwable.getClass.getCanonicalName)
      .flatMap {
        errorType => Sync[F].delay(logger.error(s"$message. Type: $errorType; Message: ${throwable.getMessage}"))
      }
}

object Logger {
  def apply[F[_]](implicit logger: Logger[F]): Logger[F] = logger

  def apply[F[_]: Sync, A: ClassTag]: Logger[F] = Logger[F](TypesafeLogger[A])
}
