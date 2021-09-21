package com.ruchij.migration.config

import pureconfig.ConfigObjectSource
import pureconfig.error.ConfigReaderException
import pureconfig.generic.auto._

case class MigrationServiceConfiguration(
  databaseConfiguration: DatabaseConfiguration,
  adminConfiguration: AdminConfiguration
)

object MigrationServiceConfiguration {
  def load(
    configObjectSource: ConfigObjectSource
  ): Either[ConfigReaderException[MigrationServiceConfiguration], MigrationServiceConfiguration] =
    configObjectSource
      .load[MigrationServiceConfiguration]
      .left
      .map(throwable => ConfigReaderException[MigrationServiceConfiguration](throwable))
}
