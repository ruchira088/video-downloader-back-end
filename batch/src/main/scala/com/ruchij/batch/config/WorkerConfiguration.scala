package com.ruchij.batch.config

import org.joda.time.LocalTime

final case class WorkerConfiguration(
  maxConcurrentDownloads: Int,
  startTime: LocalTime,
  endTime: LocalTime,
  owner: String
)
