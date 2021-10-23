package com.ruchij.api.config

import scala.concurrent.duration.FiniteDuration

case class AuthenticationConfiguration(sessionDuration: FiniteDuration)