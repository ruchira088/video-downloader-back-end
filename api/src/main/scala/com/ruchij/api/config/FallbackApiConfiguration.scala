package com.ruchij.api.config

import org.http4s.Uri

import scala.concurrent.duration.FiniteDuration

final case class FallbackApiConfiguration(uri: Uri, bearerToken: String, pollInterval: FiniteDuration)
