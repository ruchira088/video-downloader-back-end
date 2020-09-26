package com.ruchij.core.daos.resource

import com.ruchij.core.daos.resource.models.FileResource


trait FileResourceDao[F[_]] {
  def insert(resource: FileResource): F[Int]

  def getById(id: String): F[Option[FileResource]]

  def findByPath(path: String): F[Option[FileResource]]

  def deleteById(id: String): F[Int]
}