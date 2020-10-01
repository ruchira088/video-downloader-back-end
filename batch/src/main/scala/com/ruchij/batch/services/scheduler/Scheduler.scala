package com.ruchij.batch.services.scheduler

import com.ruchij.core.daos.video.models.Video
import fs2.Stream

trait Scheduler[F[_]] {
  type InitializationResult

  val run: Stream[F, Video]

  val init: F[InitializationResult]
}
