package com.ruchij.api.config

import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

import scala.concurrent.duration.FiniteDuration

final case class AuthenticationConfiguration(sessionDuration: FiniteDuration)

object AuthenticationConfiguration {
  implicit val configReader: ConfigReader[AuthenticationConfiguration] = deriveReader[AuthenticationConfiguration]
}
