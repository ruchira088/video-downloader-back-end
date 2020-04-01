package com.ruchij.config

import scala.concurrent.duration.FiniteDuration

case class WorkerConfiguration(maxConcurrentDownloads: Int, idleTimeout: FiniteDuration)
