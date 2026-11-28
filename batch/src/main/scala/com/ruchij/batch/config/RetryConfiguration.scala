package com.ruchij.batch.config

import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

import scala.concurrent.duration.{DurationInt, FiniteDuration}

final case class RetryConfiguration(maxAttempts: Int, baseDelay: FiniteDuration, maxDelay: FiniteDuration)

object RetryConfiguration {
  given configReader: ConfigReader[RetryConfiguration] = deriveReader[RetryConfiguration]

  val Default: RetryConfiguration = RetryConfiguration(maxAttempts = 5, baseDelay = 1.minute, maxDelay = 30.minutes)

  val Disabled: RetryConfiguration = Default.copy(maxAttempts = 0)
}
