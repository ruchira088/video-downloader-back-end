package com.ruchij.core.daos.workers

import com.ruchij.core.daos.workers.models.Worker
import org.joda.time.DateTime

trait WorkerDao[F[_]] {
  val idleWorker: F[Option[Worker]]

  def insert(worker: Worker): F[Int]

  def getById(workerId: String): F[Option[Worker]]

  def getByInstanceId(instanceId: String): F[List[Worker]]

  def reserveWorker(workerId: String, instanceId: String, timestamp: DateTime): F[Option[Worker]]

  def assignTask(workerId: String, scheduledVideoId: String, timestamp: DateTime): F[Option[Worker]]

  def completeTask(workerId: String, scheduledVideoId: String, timestamp: DateTime): F[Option[Worker]]

  def releaseWorker(workerId: String): F[Option[Worker]]
}
