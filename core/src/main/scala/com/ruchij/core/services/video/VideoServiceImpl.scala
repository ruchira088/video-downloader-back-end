package com.ruchij.core.services.video

import cats.{Applicative, ApplicativeError, Monad, MonadThrow, ~>}
import cats.data.OptionT
import cats.implicits._
import com.ruchij.core.daos.permission.VideoPermissionDao
import com.ruchij.core.daos.resource.FileResourceDao
import com.ruchij.core.daos.scheduling.SchedulingDao
import com.ruchij.core.daos.scheduling.models.RangeValue
import com.ruchij.core.daos.scheduling.models.SchedulingStatus.Queued
import com.ruchij.core.daos.snapshot.SnapshotDao
import com.ruchij.core.daos.snapshot.models.Snapshot
import com.ruchij.core.daos.title.VideoTitleDao
import com.ruchij.core.daos.video.VideoDao
import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.daos.videowatchhistory.VideoWatchHistoryDao
import com.ruchij.core.exceptions.ResourceNotFoundException
import com.ruchij.core.services.models.Order
import com.ruchij.core.services.models.SortBy
import com.ruchij.core.services.repository.RepositoryService
import com.ruchij.core.types.JodaClock

class VideoServiceImpl[F[_]: Monad: JodaClock, G[_]: MonadThrow](
  repositoryService: RepositoryService[F],
  videoDao: VideoDao[G],
  videoWatchHistoryDao: VideoWatchHistoryDao[G],
  snapshotDao: SnapshotDao[G],
  fileResourceDao: FileResourceDao[G],
  videoTitleDao: VideoTitleDao[G],
  videoPermissionDao: VideoPermissionDao[G],
  schedulingDao: SchedulingDao[G],
)(implicit transaction: G ~> F)
    extends VideoService[F, G] {

  override def findVideoById(videoId: String, maybeUserId: Option[String]): G[Video] =
    OptionT(videoDao.findById(videoId, maybeUserId))
      .getOrElseF {
        ApplicativeError[G, Throwable].raiseError {
          ResourceNotFoundException(s"Unable to find video with Id=$videoId")
        }
      }

  override def deleteById(videoId: String, deleteVideoFile: Boolean): F[Video] =
    transaction {
      findVideoById(videoId, None)
        .flatMap[(Video, Seq[Snapshot])] { video =>
          videoTitleDao
            .delete(Some(videoId), None)
            .productR(videoPermissionDao.delete(None, Some(videoId)))
            .productR(schedulingDao.deleteById(videoId))
            .productR(snapshotDao.findByVideo(videoId, None))
            .flatTap { snapshots =>
              snapshotDao
                .deleteByVideo(videoId)
                .productR { snapshots.traverse(snapshot => fileResourceDao.deleteById(snapshot.fileResource.id)) }
            }
            .productL(videoWatchHistoryDao.deleteBy(videoId))
            .productL(videoDao.deleteById(videoId))
            .productL(fileResourceDao.deleteById(video.fileResource.id))
            .map(snapshots => video -> snapshots)
        }
    }.flatMap {
      case (video, snapshots) =>
        snapshots
          .traverse(snapshot => repositoryService.delete(snapshot.fileResource.path))
          .productR {
            if (deleteVideoFile) repositoryService.delete(video.fileResource.path) else Applicative[F].pure(false)
          }
          .as(video)
    }

  override val queueIncorrectlyCompletedVideos: F[Seq[Video]] =
    transaction {
      videoDao.search(
        None,
        None,
        RangeValue.all,
        // 0 - 1MB
        RangeValue(None, Some(1 * 1024 * 1024)),
        0,
        100,
        SortBy.Date,
        Order.Descending,
        None,
        None
      )
    }.flatMap { videos =>
      videos.traverse { video =>
        JodaClock[F].timestamp
          .flatMap { timestamp =>
            transaction {
              videoWatchHistoryDao
                .deleteBy(video.id)
                .productR {
                  snapshotDao
                    .findByVideo(video.id, None)
                    .productL(snapshotDao.deleteByVideo(video.id))
                    .flatMap(_.traverse(snapshot => fileResourceDao.deleteById(snapshot.fileResource.id).as(snapshot)))
                }
                .productL(videoDao.deleteById(video.id))
                .productL(fileResourceDao.deleteById(video.fileResource.id))
                .productL {
                  schedulingDao.updateSchedulingStatusById(video.id, Queued, timestamp)
                }
            }
          }
          .flatMap { snapshots =>
            snapshots
              .traverse { snapshot =>
                repositoryService.delete(snapshot.fileResource.path)
              }
              .product(repositoryService.delete(video.fileResource.path))
              .as(video)
          }
      }
    }
}
