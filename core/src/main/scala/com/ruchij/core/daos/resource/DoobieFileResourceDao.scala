package com.ruchij.core.daos.resource

import cats.Applicative
import com.ruchij.core.daos.doobie.DoobieCustomMappings._
import com.ruchij.core.daos.resource.models.FileResource
import doobie.ConnectionIO
import doobie.implicits._
import doobie.util.fragments.setOpt

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

  override def update(id: String, maybeSize: Option[Long]): ConnectionIO[Int] =
    if (List(maybeSize).exists(_.nonEmpty))
      (fr"UPDATE file_resource" ++ setOpt(maybeSize.map(size => fr"size = $size")) ++ fr"WHERE id = $id")
        .update
        .run
    else Applicative[ConnectionIO].pure(0)

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
