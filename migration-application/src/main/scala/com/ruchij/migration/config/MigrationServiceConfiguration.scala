package com.ruchij.migration.config

import pureconfig.ConfigObjectSource
import pureconfig.ConfigReader
import pureconfig.error.ConfigReaderException

final case class MigrationServiceConfiguration(
  databaseConfiguration: DatabaseConfiguration,
  adminConfiguration: AdminConfiguration
) derives ConfigReader

object MigrationServiceConfiguration {
  def load(
    configObjectSource: ConfigObjectSource
  ): Either[ConfigReaderException[MigrationServiceConfiguration], MigrationServiceConfiguration] =
    configObjectSource
      .load[MigrationServiceConfiguration]
      .left
      .map(throwable => ConfigReaderException[MigrationServiceConfiguration](throwable))
}
