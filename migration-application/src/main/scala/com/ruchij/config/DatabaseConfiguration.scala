package com.ruchij.config

import pureconfig.ConfigObjectSource
import pureconfig.error.ConfigReaderException
import pureconfig.generic.auto._

case class DatabaseConfiguration(url: String, user: String, password: String)

object DatabaseConfiguration {
  def load(
    configObjectSource: ConfigObjectSource
  ): Either[ConfigReaderException[DatabaseConfiguration], DatabaseConfiguration] =
    configObjectSource
      .at("database-configuration")
      .load[DatabaseConfiguration]
      .left
      .map(throwable => ConfigReaderException[DatabaseConfiguration](throwable))
}
