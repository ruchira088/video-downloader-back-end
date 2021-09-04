package com.ruchij.batch.services.snapshots

import com.ruchij.core.daos.resource.models.FileResource

import scala.concurrent.duration.FiniteDuration

trait VideoSnapshotService[F[_]] {
  def takeSnapshot(videoFileKey: String, videoTimestamp: FiniteDuration, snapshotDestination: String): F[FileResource]
}
