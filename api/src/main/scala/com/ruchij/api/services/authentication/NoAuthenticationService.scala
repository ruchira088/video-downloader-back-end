package com.ruchij.api.services.authentication

import cats.ApplicativeError
import com.ruchij.api.daos.user.models.{Email, User}
import com.ruchij.api.exceptions.AuthenticationException
import com.ruchij.api.services.authentication.AuthenticationService.{Password, Secret}
import com.ruchij.api.services.authentication.models.AuthenticationToken

class NoAuthenticationService[F[_]: ApplicativeError[*[_], Throwable]] extends AuthenticationService[F] {
  override def login(email: Email, password: Password): F[AuthenticationToken] =
    ApplicativeError[F, Throwable].raiseError(AuthenticationException.AuthenticationDisabled)

  override def authenticate(secret: Secret): F[(AuthenticationToken, User)] =
    ApplicativeError[F, Throwable].raiseError(AuthenticationException.AuthenticationDisabled)

  override def logout(secret: Secret): F[AuthenticationToken] =
    ApplicativeError[F, Throwable].raiseError(AuthenticationException.AuthenticationDisabled)
}
