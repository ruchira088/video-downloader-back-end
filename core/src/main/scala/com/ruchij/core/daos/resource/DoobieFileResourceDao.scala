package com.ruchij.core.daos.resource

import com.ruchij.core.daos.doobie.DoobieCustomMappings._
import com.ruchij.core.daos.resource.models.FileResource
import doobie.ConnectionIO
import doobie.implicits._

object DoobieFileResourceDao extends FileResourceDao[ConnectionIO] {

  override def insert(fileResource: FileResource): ConnectionIO[Int] =
    sql"""
      INSERT INTO file_resource (id, created_at, path, media_type, size)
        VALUES (
          ${fileResource.id},
          ${fileResource.createdAt},
          ${fileResource.path},
          ${fileResource.mediaType},
          ${fileResource.size}
        )
    """.update.run

  override def update(id: String, size: Long): ConnectionIO[Int] =
    sql"UPDATE file_resource SET size = $size WHERE id = $id".update.run

  override def getById(id: String): ConnectionIO[Option[FileResource]] =
    sql"SELECT id, created_at, path, media_type, size FROM file_resource WHERE id = $id"
      .query[FileResource]
      .option

  override def findByPath(path: String): ConnectionIO[Option[FileResource]] =
    sql"SELECT id, created_at, path, media_type, size FROM file_resource WHERE path LIKE ${"%" + path}"
      .query[FileResource]
      .option

  override def deleteById(id: String): ConnectionIO[Int] =
    sql"DELETE FROM file_resource WHERE id = $id"
      .update
      .run
}
