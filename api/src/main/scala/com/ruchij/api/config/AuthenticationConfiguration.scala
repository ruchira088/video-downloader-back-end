package com.ruchij.api.config

import com.ruchij.api.services.authentication.AuthenticationService.Password
import com.ruchij.core.config.PureConfigReaders
import pureconfig.ConfigReader

import scala.concurrent.duration.FiniteDuration
import scala.util.Success

case class AuthenticationConfiguration(password: Password, sessionDuration: FiniteDuration)

object AuthenticationConfiguration {
  implicit val passwordPureConfigReader: ConfigReader[Password] =
    PureConfigReaders.tryConfigParser[Password] {
      input => Success(Password(input))
    }
}