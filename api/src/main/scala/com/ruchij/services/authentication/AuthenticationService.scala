package com.ruchij.services.authentication

import com.ruchij.services.authentication.AuthenticationService.{Password, Secret}
import com.ruchij.services.authentication.models.AuthenticationToken

trait AuthenticationService[F[_]] {
  def login(password: Password): F[AuthenticationToken]

  def authenticate(secret: Secret): F[AuthenticationToken]

  def logout(secret: Secret): F[AuthenticationToken]
}

object AuthenticationService {
  case class Secret(value: String) extends AnyVal
  case class Password(value: String) extends AnyVal
}
