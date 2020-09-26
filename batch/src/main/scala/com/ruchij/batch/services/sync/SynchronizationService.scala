package com.ruchij.batch.services.sync

import com.ruchij.batch.services.sync.models.SynchronizationResult

trait SynchronizationService[F[_]] {
  val sync: F[SynchronizationResult]
}