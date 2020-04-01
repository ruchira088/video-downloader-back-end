package com.ruchij.services.worker

trait WorkerFactory[F[_], A <: Worker[F]] {
  val newWorker: F[A]
}
