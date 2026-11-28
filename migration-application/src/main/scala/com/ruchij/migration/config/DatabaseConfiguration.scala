package com.ruchij.migration.config

import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

final case class DatabaseConfiguration(url: String, user: String, password: String)

object DatabaseConfiguration {
  implicit val configReader: ConfigReader[DatabaseConfiguration] = deriveReader[DatabaseConfiguration]
}
