package com.ruchij.batch.config

import com.ruchij.core.config.PureConfigReaders.given
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

import java.time.LocalTime

final case class WorkerConfiguration(
  maxConcurrentDownloads: Int,
  startTime: LocalTime,
  endTime: LocalTime,
  owner: String
)

object WorkerConfiguration {
  given configReader: ConfigReader[WorkerConfiguration] = deriveReader[WorkerConfiguration]
}
