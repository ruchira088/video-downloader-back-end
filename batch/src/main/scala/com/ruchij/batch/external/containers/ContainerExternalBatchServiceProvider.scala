package com.ruchij.batch.external.containers

import cats.effect.kernel.Sync
import com.ruchij.batch.external.ExternalBatchServiceProvider
import com.ruchij.core.external.ExternalCoreServiceProvider
import com.ruchij.core.external.containers.ContainerExternalCoreServiceProvider

class ContainerExternalBatchServiceProvider[F[_]: Sync] extends ExternalBatchServiceProvider[F] {
  override protected val externalCoreServiceProvider: ExternalCoreServiceProvider[F] =
    new ContainerExternalCoreServiceProvider[F]
}
