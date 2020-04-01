package com.ruchij.services.scheduler

trait Scheduler[F[_]] {
  type Result

  val run: F[Result]
}
