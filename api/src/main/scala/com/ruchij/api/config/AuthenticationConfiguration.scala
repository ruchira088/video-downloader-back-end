package com.ruchij.api.config

import com.ruchij.core.config.PureConfigReaders
import pureconfig.ConfigReader
import pureconfig.generic.auto._

import scala.concurrent.duration.FiniteDuration
import scala.util.Success

sealed trait AuthenticationConfiguration

object AuthenticationConfiguration {
  case class HashedPassword(value: String) extends AnyVal

  case class PasswordAuthenticationConfiguration(hashedPassword: HashedPassword, sessionDuration: FiniteDuration)
      extends AuthenticationConfiguration

  case object NoAuthenticationConfiguration extends AuthenticationConfiguration

  implicit val hashedPasswordPureConfigReader: ConfigReader[HashedPassword] =
    PureConfigReaders.stringConfigParserTry[HashedPassword] { input =>
      Success(HashedPassword(input))
    }

  implicit val authenticationConfigReader: ConfigReader[AuthenticationConfiguration] =
    ConfigReader[PasswordAuthenticationConfiguration].map[AuthenticationConfiguration](identity)
}
