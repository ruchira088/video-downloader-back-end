package com.ruchij.api.web.requests

import cats.Applicative

trait Validator[F[_], -A] {
  def validate[B <: A](value: B): F[B]
}

object Validator {
  implicit def noValidator[F[_]: Applicative]: Validator[F, Any] = new Validator[F, Any] {
    override def validate[B <: Any](value: B): F[B] = Applicative[F].pure(value)
  }
}