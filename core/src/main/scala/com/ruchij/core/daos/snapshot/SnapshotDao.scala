package com.ruchij.core.daos.snapshot

import com.ruchij.core.daos.snapshot.models.Snapshot

trait SnapshotDao[F[_]] {
  def insert(snapshot: Snapshot): F[Int]

  /** Returns the snapshots of the video ordered by their video timestamp. */
  def findByVideo(videoId: String, maybeUserId: Option[String]): F[Seq[Snapshot]]

  def hasPermission(snapshotFileResourceId: String, userId: String): F[Boolean]

  def isSnapshotFileResource(fileResourceId: String): F[Boolean]

  def deleteByVideo(videoId: String): F[Int]
}
