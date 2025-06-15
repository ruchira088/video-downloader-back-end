package com.ruchij.api.web.middleware

import cats.ApplicativeError
import com.ruchij.api.exceptions.AuthorizationException
import org.http4s.Response

object Authorizer {
  def apply[F[_]: ApplicativeError[*[_], Throwable]](
    hasPermission: Boolean,
    errorMessage: String = "User is not authorized perform this action"
  )(block: => F[Response[F]]): F[Response[F]] =
    if (hasPermission) block else ApplicativeError[F, Throwable].raiseError(AuthorizationException(errorMessage))
}
