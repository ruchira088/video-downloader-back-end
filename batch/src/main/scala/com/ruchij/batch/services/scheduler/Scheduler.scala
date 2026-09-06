package com.ruchij.batch.services.scheduler

import com.ruchij.core.daos.video.models.Video
import fs2.Stream

import scala.util.control.NoStackTrace

trait Scheduler[F[_]] {
  type InitializationResult

  val run: Stream[F, Video]

  val init: F[InitializationResult]
}

object Scheduler {
  /** Used to interrupt a running download; it is control flow, not a failure, so no stack trace is captured. */
  case object PausedVideoDownload extends Exception with NoStackTrace
}