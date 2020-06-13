package com.ruchij.daos.snapshot

import com.ruchij.daos.snapshot.models.Snapshot

trait SnapshotDao[F[_]] {
  def insert(snapshot: Snapshot): F[Int]

  def findByVideo(videoId: String): F[Seq[Snapshot]]
}
