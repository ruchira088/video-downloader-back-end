package com.ruchij.api.services.background

import cats.effect.Fiber
import com.ruchij.core.services.scheduling.models.DownloadProgress
import fs2.Stream

trait BackgroundService[F[_]] {
  type Result

  val run: F[Fiber[F, Result]]

  val downloadProgress: Stream[F, DownloadProgress]
}
