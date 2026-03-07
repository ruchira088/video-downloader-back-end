package com.ruchij.api.services.detection

import com.ruchij.core.daos.duplicate.models.DuplicateVideo

trait ApiDuplicateDetectionService[F[_]] {
  def findDuplicateVideos(offset: Int, limit: Int): F[Map[String, Set[DuplicateVideo]]]

  def getDuplicateVideoGroup(groupId: String): F[Seq[DuplicateVideo]]

  def duplicateVideoGroups: F[Seq[String]]

  def deleteVideo(videoId: String): F[Option[DuplicateVideo]]
}
