package com.ruchij.core.exceptions

final case class ValidationException(message: String) extends Exception(message)
