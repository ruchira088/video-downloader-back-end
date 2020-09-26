package com.ruchij.batch.services.scheduler

trait Scheduler[F[_]] {
  type Result

  type InitializationResult

  val run: F[Result]

  val init: F[InitializationResult]
}
