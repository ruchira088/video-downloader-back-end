package com.ruchij.core.config

import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

final case class SentryConfiguration(dsn: Option[String], environment: String, tracesSampleRate: Double)

object SentryConfiguration {
  given configReader: ConfigReader[SentryConfiguration] = deriveReader[SentryConfiguration]
}
