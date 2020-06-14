package com.ruchij.daos.resource

import com.ruchij.daos.resource.models.FileResource

trait FileResourceDao[F[_]] {
  def insert(resource: FileResource): F[Int]

  def getById(id: String): F[Option[FileResource]]

  def findByPath(path: String): F[Option[FileResource]]
}