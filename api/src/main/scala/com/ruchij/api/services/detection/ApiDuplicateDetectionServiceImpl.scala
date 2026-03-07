package com.ruchij.api.services.detection

import cats.data.OptionT
import cats.implicits._
import cats.{Applicative, Functor, Monad, ~>}
import com.ruchij.core.daos.duplicate.DuplicateVideoDao
import com.ruchij.core.daos.duplicate.models.DuplicateVideo
import com.ruchij.core.daos.hash.VideoPerceptualHashDao

class ApiDuplicateDetectionServiceImpl[F[_]: Functor, G[_]: Monad](
  duplicateVideoDao: DuplicateVideoDao[G],
  videoPerceptualHashDao: VideoPerceptualHashDao[G]
)(implicit transaction: G ~> F)
    extends ApiDuplicateDetectionService[F] {

  override def findDuplicateVideos(offset: Int, limit: Int): F[Map[String, Set[DuplicateVideo]]] =
    transaction {
      duplicateVideoDao.getAll(offset = offset, limit = limit)
    }.map { duplicates =>
      duplicates.groupBy(_.duplicateGroupId).view.mapValues(_.toSet).toMap
    }

  override def getDuplicateVideoGroup(groupId: String): F[Seq[DuplicateVideo]] =
    transaction(duplicateVideoDao.findByDuplicateGroupId(groupId))

  override def duplicateVideoGroups: F[Seq[String]] = transaction(duplicateVideoDao.duplicateGroupIds)

  override def deleteVideo(videoId: String): F[Option[DuplicateVideo]] =
    transaction {
      OptionT(duplicateVideoDao.findByVideoId(videoId))
        .semiflatTap { duplicateVideo =>
          duplicateVideoDao.delete(duplicateVideo.videoId)
        }
        .semiflatTap { _ =>
          videoPerceptualHashDao.deleteByVideoId(videoId)
        }
        .semiflatTap { duplicateVideo =>
          duplicateVideoDao
            .findByDuplicateGroupId(duplicateVideo.duplicateGroupId)
            .flatMap { existingDuplicateVideosForGroup =>
              val deleteGroupVideos =
                existingDuplicateVideosForGroup.map(_.videoId).traverse(duplicateVideoDao.delete)

              if (existingDuplicateVideosForGroup.size <= 1) {
                deleteGroupVideos.void
              } else {
                val newGroupId = existingDuplicateVideosForGroup.map(_.videoId).min

                if (newGroupId == duplicateVideo.duplicateGroupId) {
                  Applicative[G].unit
                } else {
                  deleteGroupVideos.productR {
                    existingDuplicateVideosForGroup
                      .map(_.copy(duplicateGroupId = newGroupId))
                      .traverse(duplicateVideoDao.insert)
                  }.void
                }
              }
            }
        }
        .value
    }
}
