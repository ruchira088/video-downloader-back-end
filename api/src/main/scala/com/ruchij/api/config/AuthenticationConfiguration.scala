package com.ruchij.api.config

import com.ruchij.api.daos.credentials.models.Credentials.HashedPassword
import com.ruchij.core.config.PureConfigReaders
import pureconfig.ConfigReader

import scala.concurrent.duration.FiniteDuration
import scala.util.Success

case class AuthenticationConfiguration(sessionDuration: FiniteDuration)

object AuthenticationConfiguration {
  implicit val hashedPasswordPureConfigReader: ConfigReader[HashedPassword] =
    PureConfigReaders.stringConfigParserTry[HashedPassword] { input =>
      Success(HashedPassword(input))
    }
}
