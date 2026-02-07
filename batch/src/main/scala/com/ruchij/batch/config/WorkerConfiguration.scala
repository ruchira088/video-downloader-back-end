package com.ruchij.batch.config

import java.time.LocalTime

final case class WorkerConfiguration(
  maxConcurrentDownloads: Int,
  startTime: LocalTime,
  endTime: LocalTime,
  owner: String
)
