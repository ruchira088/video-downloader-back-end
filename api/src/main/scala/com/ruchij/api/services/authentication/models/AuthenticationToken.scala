package com.ruchij.api.services.authentication.models

import com.ruchij.api.services.authentication.AuthenticationService.Secret
import com.ruchij.core.kv.keys.{KVStoreKey, KeySpace}
import java.time.Instant

import scala.concurrent.duration._
import scala.language.postfixOps

final case class AuthenticationToken(userId: String, secret: Secret, expiresAt: Instant, issuedAt: Instant, renewals: Long)

object AuthenticationToken {
  final case class AuthenticationTokenKey(secret: Secret) extends KVStoreKey

  implicit case object AuthenticationKeySpace extends KeySpace[AuthenticationTokenKey, AuthenticationToken] {
    override val name: String = "authentication"

    override val maybeTtl: Option[FiniteDuration] = Some(45 days)
  }
}
