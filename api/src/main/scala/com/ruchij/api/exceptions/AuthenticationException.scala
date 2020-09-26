package com.ruchij.api.exceptions

case class AuthenticationException(message: String) extends Exception(message)
