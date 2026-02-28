package com.ruchij.api.services.detection

import cats.Functor
import cats.implicits._
import cats.~>
import com.ruchij.core.daos.duplicate.DuplicateVideoDao
import com.ruchij.core.daos.duplicate.models.DuplicateVideo

class DuplicateDetectionServiceImpl[F[_]: Functor, G[_]](duplicateVideoDao: DuplicateVideoDao[G])(
  implicit transaction: G ~> F
) extends DuplicateDetectionService[F] {

  override def findDuplicateVideos(offset: Int, limit: Int): F[Map[String, Set[DuplicateVideo]]] =
    transaction {
      duplicateVideoDao.getAll(offset = offset, limit = limit)
    }.map { duplicates =>
        duplicates.groupBy(_.duplicateGroupId).view.mapValues(_.toSet).toMap
      }

  override def getDuplicateVideoGroup(groupId: String): F[Seq[DuplicateVideo]] =
    transaction(duplicateVideoDao.findByDuplicateGroupId(groupId))

  override def duplicateVideoGroups: F[Seq[String]] = transaction(duplicateVideoDao.duplicateGroupIds)
}
