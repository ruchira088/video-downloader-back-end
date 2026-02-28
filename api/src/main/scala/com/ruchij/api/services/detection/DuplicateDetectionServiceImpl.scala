package com.ruchij.api.services.detection

import cats.effect.kernel.Sync
import cats.implicits._
import cats.~>
import com.ruchij.core.daos.duplicate.DuplicateVideoDao
import com.ruchij.core.daos.duplicate.models.DuplicateVideo

class DuplicateDetectionServiceImpl[F[_]: Sync, G[_]](duplicateVideoDao: DuplicateVideoDao[G])(
  implicit transaction: G ~> F
) extends DuplicateDetectionService[F] {

  override def findDuplicateVideos(offset: Int, limit: Int): F[Set[Set[DuplicateVideo]]] =
    transaction {
      duplicateVideoDao.getAll(offset = offset, limit = limit)
    }.map { duplicates =>
        duplicates.groupBy(_.duplicateGroupId).view.values.map(_.toSet).toSet
      }

  override def getDuplicateVideoGroup(groupId: String): F[Seq[DuplicateVideo]] =
    transaction(duplicateVideoDao.findByDuplicateGroupId(groupId))

  override val duplicateVideoGroups: F[Seq[String]] = transaction(duplicateVideoDao.duplicateGroupIds)
}
