package com.ruchij.core.config

final case class SentryConfiguration(
  dsn: Option[String],
  environment: String,
  tracesSampleRate: Double
)
