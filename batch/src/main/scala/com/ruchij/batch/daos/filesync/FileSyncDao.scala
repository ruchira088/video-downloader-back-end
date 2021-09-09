package com.ruchij.batch.daos.filesync

import com.ruchij.batch.daos.filesync.models.FileSync
import org.joda.time.DateTime

trait FileSyncDao[F[_]] {
  def insert(fileSync: FileSync): F[Int]

  def findByPath(path: String): F[Option[FileSync]]

  def complete(path: String, timestamp: DateTime): F[Option[FileSync]]

  def deleteByPath(path: String): F[Option[FileSync]]
}
