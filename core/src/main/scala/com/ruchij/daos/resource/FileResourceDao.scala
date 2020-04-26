package com.ruchij.daos.resource

import com.ruchij.daos.resource.models.FileResource
import doobie.ConnectionIO

trait FileResourceDao[F[_]] {
  def insert(resource: FileResource): ConnectionIO[Int]

  def getById(id: String): F[Option[FileResource]]
}