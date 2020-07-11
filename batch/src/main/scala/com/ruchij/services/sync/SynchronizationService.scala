package com.ruchij.services.sync

import com.ruchij.services.sync.models.SynchronizationResult

trait SynchronizationService[F[_]] {
  val sync: F[SynchronizationResult]
}