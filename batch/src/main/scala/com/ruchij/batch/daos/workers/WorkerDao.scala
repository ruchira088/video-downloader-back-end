package com.ruchij.batch.daos.workers

import com.ruchij.batch.daos.workers.models.Worker
import com.ruchij.core.daos.workers.models.WorkerStatus
import org.joda.time.DateTime

trait WorkerDao[F[_]] {
  val idleWorker: F[Option[Worker]]

  def insert(worker: Worker): F[Int]

  def getById(workerId: String): F[Option[Worker]]

  def reserveWorker(workerId: String, timestamp: DateTime): F[Option[Worker]]

  def assignTask(workerId: String, scheduledVideoId: String, timestamp: DateTime): F[Option[Worker]]

  def completeTask(workerId: String, scheduledVideoId: String, timestamp: DateTime): F[Option[Worker]]

  def releaseWorker(workerId: String): F[Option[Worker]]

  def updateHeartBeat(workerId: String, timestamp: DateTime): F[Option[Worker]]

  def cleanUpStaleWorkers(heartBeatBefore: DateTime): F[Int]

  def updateWorkerStatuses(workerStatus: WorkerStatus): F[Seq[Worker]]
}
