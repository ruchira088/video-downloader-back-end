package com.ruchij.daos.resource

import cats.effect.Bracket
import com.ruchij.daos.resource.models.FileResource
import com.ruchij.daos.doobie.DoobieCustomMappings._
import doobie.ConnectionIO
import doobie.implicits._
import doobie.util.transactor.Transactor

class DoobieFileResourceDao[F[_]: Bracket[*[_], Throwable]](transactor: Transactor.Aux[F, Unit])
    extends FileResourceDao[F] {
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

  override def getById(id: String): F[Option[FileResource]] =
    sql"SELECT id, created_at, path, media_type, size FROM file_resource WHERE id = $id"
      .query[FileResource]
      .option
      .transact(transactor)

  override def findByPath(path: String): F[Option[FileResource]] =
    sql"SELECT id, created_at, path, media_type, size FROM file_resource WHERE path LIKE ${"%" + path}"
      .query[FileResource]
      .option
      .transact(transactor)
}
