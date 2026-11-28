package com.ruchij.core.config

import pureconfig.ConfigReader

final case class SentryConfiguration(dsn: Option[String], environment: String, tracesSampleRate: Double)
    derives ConfigReader
