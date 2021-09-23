package com.ruchij.api.web.requests

import cats.MonadError
import cats.implicits._
import org.http4s.{AuthedRequest, EntityDecoder, Request}

object RequestOps {
  def to[F[_]: MonadError[*[_], Throwable], A](
    request: Request[F]
  )(implicit entityDecoder: EntityDecoder[F, A], validator: Validator[F, A]): F[A] =
    for {
      entity <- request.as[A]
      validatedEntity <- validator.validate[A](entity)
    } yield validatedEntity

  implicit class RequestOpsSyntax[F[_]: MonadError[*[_], Throwable]](request: Request[F]) {
    def to[A](implicit entityDecoder: EntityDecoder[F, A], validator: Validator[F, A]): F[A] =
      RequestOps.to[F, A](request)
  }

  implicit class AuthRequestOpsSyntax[F[_]: MonadError[*[_], Throwable], T](authRequest: AuthedRequest[F, T]) {
    def to[A](implicit entityDecoder: EntityDecoder[F, A], validator: Validator[F, A]): F[A] =
      RequestOps.to[F, A](authRequest.req)
  }
}
