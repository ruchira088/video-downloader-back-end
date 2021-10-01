package com.ruchij.core.daos.snapshot

import com.ruchij.core.daos.snapshot.models.Snapshot

trait SnapshotDao[F[_]] {
  def insert(snapshot: Snapshot): F[Int]

  def findByVideo(videoId: String, maybeUserId: Option[String]): F[Seq[Snapshot]]

  def deleteByVideo(videoId: String): F[Int]
}
