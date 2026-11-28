package com.ruchij.migration.config

import pureconfig.ConfigObjectSource
import pureconfig.ConfigReader
import pureconfig.error.ConfigReaderException
import pureconfig.generic.semiauto.deriveReader

final case class MigrationServiceConfiguration(
  databaseConfiguration: DatabaseConfiguration,
  adminConfiguration: AdminConfiguration
)

object MigrationServiceConfiguration {
  given configReader: ConfigReader[MigrationServiceConfiguration] = deriveReader[MigrationServiceConfiguration]

  def load(
    configObjectSource: ConfigObjectSource
  ): Either[ConfigReaderException[MigrationServiceConfiguration], MigrationServiceConfiguration] =
    configObjectSource
      .load[MigrationServiceConfiguration]
      .left
      .map(throwable => ConfigReaderException[MigrationServiceConfiguration](throwable))
}
