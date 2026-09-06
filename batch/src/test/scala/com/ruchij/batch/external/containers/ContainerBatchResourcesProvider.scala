package com.ruchij.batch.external.containers

import cats.effect.kernel.Sync
import com.ruchij.batch.external.BatchResourcesProvider
import com.ruchij.core.external.CoreResourcesProvider
import com.ruchij.core.external.containers.ContainerCoreResourcesProvider

class ContainerBatchResourcesProvider[F[_]: Sync] extends BatchResourcesProvider[F] {
  override protected val externalCoreServiceProvider: CoreResourcesProvider[F] =
    new ContainerCoreResourcesProvider[F]
}
