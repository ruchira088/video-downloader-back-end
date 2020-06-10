package com.ruchij.services.sync

import com.ruchij.services.sync.models.SyncResult

trait SynchronizationService[F[_]] {
  val sync: F[SyncResult]
}