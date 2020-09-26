package com.ruchij.core.exceptions

case class ResourceNotFoundException(errorMessage: String) extends Exception(errorMessage)
