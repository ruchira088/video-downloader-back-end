package com.ruchij.batch.daos.filesync

import cats.data.OptionT
import cats.implicits._
import com.ruchij.batch.daos.filesync.models.FileSync
import com.ruchij.core.daos.doobie.DoobieUtils.SingleUpdateOps
import com.ruchij.core.daos.doobie.DoobieCustomMappings.{dateTimeGet, dateTimePut}
import doobie.free.connection.ConnectionIO
import doobie.implicits.toSqlInterpolator
import org.joda.time.DateTime

object DoobieFileSyncDao extends FileSyncDao[ConnectionIO] {

  override def insert(fileSync: FileSync): ConnectionIO[Int] =
    sql"INSERT INTO file_sync (locked_at, path) VALUES (${fileSync.lockedAt}, ${fileSync.path})"
      .update
      .run

  override def findByPath(path: String): ConnectionIO[Option[FileSync]] =
    sql"SELECT locked_at, path, synced_at FROM file_sync WHERE path = $path"
      .query[FileSync]
      .option

  override def complete(path: String, timestamp: DateTime): ConnectionIO[Option[FileSync]] =
    sql"UPDATE file_sync SET synced_at = ${Some(timestamp)} WHERE path = $path"
      .update
      .run
      .singleUpdate
      .productR { OptionT(findByPath(path)) }
      .value

  override def deleteByPath(path: String): ConnectionIO[Option[FileSync]] =
    OptionT(findByPath(path))
      .productL {
        sql"DELETE FROM file_sync WHERE path = $path"
          .update
          .run
          .singleUpdate
      }
      .value

}
