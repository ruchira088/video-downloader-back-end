package com.ruchij.logging

import cats.effect.Sync
import com.typesafe.scalalogging.{Logger => TypesafeLogger}

import scala.reflect.ClassTag

case class Logger[F[_]: Sync](logger: TypesafeLogger) {
  def infoF(message: String): F[Unit] = Sync[F].delay(logger.info(message))

  def warnF(message: String): F[Unit] = Sync[F].delay(logger.warn(message))
}

object Logger {
  def apply[F[_]: Sync, A: ClassTag]: Logger[F] = Logger[F](TypesafeLogger[A])
}
