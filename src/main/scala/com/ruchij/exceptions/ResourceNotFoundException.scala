package com.ruchij.exceptions

case class ResourceNotFoundException(errorMessage: String) extends Exception(errorMessage)
