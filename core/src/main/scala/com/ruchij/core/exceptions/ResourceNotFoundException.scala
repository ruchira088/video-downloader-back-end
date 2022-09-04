package com.ruchij.core.exceptions

final case class ResourceNotFoundException(errorMessage: String) extends Exception(errorMessage)
