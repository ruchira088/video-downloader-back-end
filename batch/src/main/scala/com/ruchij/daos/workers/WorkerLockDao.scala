package com.ruchij.daos.workers

import cats.data.OptionT
import com.ruchij.daos.workers.models.WorkerLock
import org.joda.time.DateTime

trait WorkerLockDao[F[_]] {
  val vacantLock: OptionT[F, WorkerLock]

  def insert(workerLock: WorkerLock): F[Int]

  def acquireLock(workerLockId: String, timestamp: DateTime): OptionT[F, WorkerLock]

  def setScheduledVideoId(workerLockId: String, scheduledVideoId: String): OptionT[F, WorkerLock]

  def releaseLock(workerLockId: String): OptionT[F, WorkerLock]
}
