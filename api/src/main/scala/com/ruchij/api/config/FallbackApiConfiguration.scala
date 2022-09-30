package com.ruchij.api.config

import org.http4s.Uri

final case class FallbackApiConfiguration(uri: Uri, bearerToken: String)
