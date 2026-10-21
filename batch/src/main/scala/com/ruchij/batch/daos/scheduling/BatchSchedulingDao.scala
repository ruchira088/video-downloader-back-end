package com.ruchij.batch.daos.scheduling

import com.ruchij.core.daos.scheduling.models.SchedulingStatus

trait BatchSchedulingDao[F[_]] {

  /**
    * Locks the scheduled video download until the end of the transaction, and returns its current status, or None if
    * it does not exist. Updates that depend on the status are made in the same transaction, after the lock, so that
    * a concurrent change cannot slip in between reading the status and writing the update.
    */
  def lockStatusById(id: String): F[Option[SchedulingStatus]]
}
