package com.ruchij.api.exceptions

final case class AuthorizationException(message: String) extends Exception(message)
