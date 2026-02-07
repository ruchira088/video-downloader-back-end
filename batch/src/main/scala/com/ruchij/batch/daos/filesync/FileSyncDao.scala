package com.ruchij.batch.daos.filesync

import com.ruchij.batch.daos.filesync.models.FileSync
import java.time.Instant

trait FileSyncDao[F[_]] {
  def insert(fileSync: FileSync): F[Int]

  def findByPath(path: String): F[Option[FileSync]]

  def complete(path: String, timestamp: Instant): F[Option[FileSync]]

  def deleteByPath(path: String): F[Option[FileSync]]
}
