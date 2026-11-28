package com.ruchij.batch.config

import com.ruchij.core.config.PureConfigReaders.given
import pureconfig.ConfigReader

import java.time.LocalTime

final case class WorkerConfiguration(
  maxConcurrentDownloads: Int,
  startTime: LocalTime,
  endTime: LocalTime,
  owner: String
) derives ConfigReader
