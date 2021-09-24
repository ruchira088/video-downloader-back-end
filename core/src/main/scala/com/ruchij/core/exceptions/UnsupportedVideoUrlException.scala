package com.ruchij.core.exceptions

import org.http4s.Uri

case class UnsupportedVideoUrlException(uri: Uri) extends Exception(s"Unsupported video URL: ${uri.renderString}")
