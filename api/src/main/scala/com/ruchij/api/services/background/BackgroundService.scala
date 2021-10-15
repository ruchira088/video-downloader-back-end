package com.ruchij.api.services.background

import cats.effect.Fiber
import com.ruchij.api.services.health.models.messaging.HealthCheckMessage
import com.ruchij.core.services.scheduling.models.DownloadProgress
import fs2.Stream

trait BackgroundService[F[_]] {
  type Result

  val run: F[Fiber[F, Throwable, Result]]

  val downloadProgress: Stream[F, DownloadProgress]

  val healthChecks: Stream[F, HealthCheckMessage]
}
