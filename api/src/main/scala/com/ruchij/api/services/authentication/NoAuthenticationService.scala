package com.ruchij.api.services.authentication
import cats.ApplicativeError
import com.ruchij.api.exceptions.AuthenticationException
import com.ruchij.api.services.authentication.models.AuthenticationToken

class NoAuthenticationService[F[_]: ApplicativeError[*[_], Throwable]] extends AuthenticationService[F] {

  override def login(password: AuthenticationService.Password): F[AuthenticationToken] =
    ApplicativeError[F, Throwable].raiseError(AuthenticationException.AuthenticationDisabled)

  override def authenticate(secret: AuthenticationService.Secret): F[AuthenticationToken] =
    ApplicativeError[F, Throwable].raiseError(AuthenticationException.AuthenticationDisabled)

  override def logout(secret: AuthenticationService.Secret): F[AuthenticationToken] =
    ApplicativeError[F, Throwable].raiseError(AuthenticationException.AuthenticationDisabled)

  override val enabled: Boolean = false
}
