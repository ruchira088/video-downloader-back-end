package com.ruchij.api.services.detection

import com.ruchij.core.daos.duplicate.models.DuplicateVideo

trait DuplicateDetectionService[F[_]] {
  def findDuplicateVideos(offset: Int, limit: Int): F[Map[String, Set[DuplicateVideo]]]

  def getDuplicateVideoGroup(groupId: String): F[Seq[DuplicateVideo]]

  def duplicateVideoGroups: F[Seq[String]]
}
