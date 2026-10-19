package com.ruchij.api.exceptions

final case class AuthenticationException(message: String) extends Exception(message)

object AuthenticationException {
  val MissingAuthenticationToken: AuthenticationException =
    AuthenticationException("Authentication cookie/token not found")

  val RevokedAuthenticationToken: AuthenticationException =
    AuthenticationException("Authentication token has been revoked")

  val AuthenticationDisabled: AuthenticationException =
    AuthenticationException("Authentication is disabled")
}
