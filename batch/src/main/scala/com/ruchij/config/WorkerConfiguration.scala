package com.ruchij.config

import org.joda.time.LocalTime

case class WorkerConfiguration(maxConcurrentDownloads: Int, startTime: LocalTime, endTime: LocalTime)