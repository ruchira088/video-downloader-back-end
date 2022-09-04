package com.ruchij.api.exceptions

final case class ResourceConflictException(message: String) extends Exception(message)
