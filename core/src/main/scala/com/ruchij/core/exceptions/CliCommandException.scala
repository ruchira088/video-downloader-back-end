package com.ruchij.core.exceptions

final case class CliCommandException(error: String) extends Exception(error)
