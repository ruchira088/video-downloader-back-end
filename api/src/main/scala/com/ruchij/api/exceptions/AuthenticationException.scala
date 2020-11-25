package com.ruchij.api.exceptions

case class AuthenticationException(message: String) extends Exception(message)

object AuthenticationException {
  val MissingAuthenticationToken: AuthenticationException =
    AuthenticationException("Authentication cookie/token not found")

  val AuthenticationDisabled: AuthenticationException =
    AuthenticationException("Authentication is disabled")
}
