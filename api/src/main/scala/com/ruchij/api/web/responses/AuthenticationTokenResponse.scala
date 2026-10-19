package com.ruchij.api.web.responses

import com.ruchij.api.services.authentication.AuthenticationService.Secret
import com.ruchij.api.services.authentication.models.AuthenticationToken

import java.time.Instant

final case class AuthenticationTokenResponse(
  userId: String,
  secret: Secret,
  expiresAt: Instant,
  issuedAt: Instant,
  renewals: Long
)

object AuthenticationTokenResponse {
  def from(authenticationToken: AuthenticationToken): AuthenticationTokenResponse =
    AuthenticationTokenResponse(
      authenticationToken.userId,
      authenticationToken.secret,
      authenticationToken.expiresAt,
      authenticationToken.issuedAt,
      authenticationToken.renewals
    )
}
