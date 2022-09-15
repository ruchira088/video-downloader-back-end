package com.ruchij.fallback.services.auth

import com.ruchij.api.daos.user.models.{Email, User}
import com.ruchij.api.services.authentication.AuthenticationService
import com.ruchij.api.services.authentication.AuthenticationService.Password
import com.ruchij.api.services.authentication.models.AuthenticationToken

class AuthenticationServiceImpl[F[_]] extends AuthenticationService[F] {
  override def login(email: Email, password: Password): F[AuthenticationToken] = ???

  override def authenticate(secret: AuthenticationService.Secret): F[(AuthenticationToken, User)] = ???

  override def logout(secret: AuthenticationService.Secret): F[User] = ???
}
