package com.ruchij.core.daos.duplicate

import com.ruchij.core.daos.duplicate.models.DuplicateVideo

trait DuplicateVideoDao[F[_]] {
  def insert(duplicateVideo: DuplicateVideo): F[Int]

  def delete(videoId: String): F[Int]

  def findByVideoId(videoId: String): F[Option[DuplicateVideo]]

  def findByDuplicateGroupId(duplicateGroupId: String): F[Seq[DuplicateVideo]]

  def getAll(offset: Int, limit: Int): F[Seq[DuplicateVideo]]

  def duplicateGroupIds: F[Seq[String]]

  def deleteAll: F[Int]
}
