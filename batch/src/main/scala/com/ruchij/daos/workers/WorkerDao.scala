package com.ruchij.daos.workers

import cats.data.OptionT
import com.ruchij.daos.workers.models.Worker

trait WorkerDao[F[_]] {
  val idleWorker: OptionT[F, Worker]

  def insert(worker: Worker): F[Int]

  def reserveWorker(workerId: String): OptionT[F, Worker]

  def assignTask(workerId: String, scheduledVideoId: String): OptionT[F, Worker]

  def release(workerId: String): OptionT[F, Worker]
}
