package com.ruchij.api.config

import com.ruchij.api.config.AuthenticationConfiguration.HashedPassword
import com.ruchij.core.config.PureConfigReaders
import pureconfig.ConfigReader

import scala.concurrent.duration.FiniteDuration
import scala.util.Success

case class AuthenticationConfiguration(hashedPassword: HashedPassword, sessionDuration: FiniteDuration)

object AuthenticationConfiguration {
  case class HashedPassword(value: String) extends AnyVal

  implicit val hashedPasswordPureConfigReader: ConfigReader[HashedPassword] =
    PureConfigReaders.tryConfigParser[HashedPassword] {
      input => Success(HashedPassword(input))
    }
}