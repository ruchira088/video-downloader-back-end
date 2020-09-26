package com.ruchij.api.services.authentication.models

import com.ruchij.api.services.authentication.AuthenticationService.Secret
import com.ruchij.core.kv.keys.{KVStoreKey, KeySpace}
import org.joda.time.DateTime

case class AuthenticationToken(secret: Secret, expiresAt: DateTime, issuedAt: DateTime, renewals: Long)

object AuthenticationToken {
  case class AuthenticationTokenKey(secret: Secret) extends KVStoreKey

  implicit case object AuthenticationKeySpace extends KeySpace[AuthenticationTokenKey, AuthenticationToken] {
    override val name: String = "authentication"
  }
}
