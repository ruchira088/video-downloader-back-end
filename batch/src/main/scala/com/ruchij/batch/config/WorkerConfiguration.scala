package com.ruchij.batch.config

import org.joda.time.LocalTime

case class WorkerConfiguration(
  instanceId: String,
  maxConcurrentDownloads: Int,
  startTime: LocalTime,
  endTime: LocalTime
)
