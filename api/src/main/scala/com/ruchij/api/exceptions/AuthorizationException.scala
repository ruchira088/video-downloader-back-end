package com.ruchij.api.exceptions

case class AuthorizationException(message: String) extends Exception(message)
