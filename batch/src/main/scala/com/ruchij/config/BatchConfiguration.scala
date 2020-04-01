package com.ruchij.config

import scala.concurrent.duration.FiniteDuration

case class BatchConfiguration(workerCount: Int, idleTimeout: FiniteDuration)
