package com.ruchij.migration.config

import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

final case class AdminConfiguration(hashedAdminPassword: String)

object AdminConfiguration {
  given configReader: ConfigReader[AdminConfiguration] = deriveReader[AdminConfiguration]
}
