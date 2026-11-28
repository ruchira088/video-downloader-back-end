package com.ruchij.api.config

import pureconfig.ConfigReader

import scala.concurrent.duration.FiniteDuration

final case class AuthenticationConfiguration(sessionDuration: FiniteDuration) derives ConfigReader
