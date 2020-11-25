package com.ruchij.api.services.authentication

import java.util.UUID

import cats.effect.Sync
import cats.implicits.toFunctorOps
import com.ruchij.api.services.authentication.AuthenticationService.{Password, Secret}
import com.ruchij.api.services.authentication.models.AuthenticationToken
import com.ruchij.core.types.RandomGenerator

trait AuthenticationService[F[_]] {
  def login(password: Password): F[AuthenticationToken]

  def authenticate(secret: Secret): F[AuthenticationToken]

  def logout(secret: Secret): F[AuthenticationToken]

  val enabled: Boolean
}

object AuthenticationService {
  case class Secret(value: String) extends AnyVal
  case class Password(value: String) extends AnyVal

  implicit def secretGenerator[F[+ _]: Sync]: RandomGenerator[F, Secret] =
    RandomGenerator[F, UUID].map(uuid => Secret(uuid.toString))
}
