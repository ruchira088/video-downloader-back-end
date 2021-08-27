package com.ruchij.core.exceptions

case class CliCommandException(error: String) extends Exception(error)
