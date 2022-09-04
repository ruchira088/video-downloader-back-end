package com.ruchij.api.config

import scala.concurrent.duration.FiniteDuration

final case class AuthenticationConfiguration(sessionDuration: FiniteDuration)