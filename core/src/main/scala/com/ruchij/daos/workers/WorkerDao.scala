package com.ruchij.daos.workers

import com.ruchij.daos.workers.models.Worker
import org.joda.time.DateTime

trait WorkerDao[F[_]] {
  val idleWorker: F[Option[Worker]]

  def insert(worker: Worker): F[Int]

  def getById(workerId: String): F[Option[Worker]]

  def reserveWorker(workerId: String, timestamp: DateTime): F[Option[Worker]]

  def assignTask(workerId: String, scheduledVideoId: String, timestamp: DateTime): F[Option[Worker]]

  def release(workerId: String, timestamp: DateTime): F[Option[Worker]]

  val resetWorkers: F[Int]
}
