package com.ruchij.core.messaging.models

import org.http4s.{MediaType, Method, Status, Uri}

import scala.concurrent.duration.FiniteDuration

final case class HttpMetric(
  method: Method,
  uri: Uri,
  finiteDuration: FiniteDuration,
  responseStatus: Status,
  contentType: Option[MediaType],
  size: Option[Long]
)
