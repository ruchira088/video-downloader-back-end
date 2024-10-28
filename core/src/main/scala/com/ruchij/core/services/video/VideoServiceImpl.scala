package com.ruchij.core.services.video

import cats.{Applicative, ApplicativeError, Monad, MonadThrow, ~>}
import cats.data.OptionT
import cats.implicits._
import com.ruchij.core.daos.resource.FileResourceDao
import com.ruchij.core.daos.snapshot.SnapshotDao
import com.ruchij.core.daos.snapshot.models.Snapshot
import com.ruchij.core.daos.video.VideoDao
import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.daos.videowatchhistory.VideoWatchHistoryDao
import com.ruchij.core.exceptions.ResourceNotFoundException
import com.ruchij.core.services.repository.RepositoryService

class VideoServiceImpl[F[_]: Monad, G[_]: MonadThrow](
  repositoryService: RepositoryService[F],
  videoDao: VideoDao[G],
  videoWatchHistoryDao: VideoWatchHistoryDao[G],
  snapshotDao: SnapshotDao[G],
  fileResourceDao: FileResourceDao[G]
)(implicit transaction: G ~> F)
    extends VideoService[F, G] {

  override def findVideoById(videoId: String, maybeUserId: Option[String]): G[Video] =
    OptionT(videoDao.findById(videoId, maybeUserId))
      .getOrElseF {
        ApplicativeError[G, Throwable].raiseError {
          ResourceNotFoundException(s"Unable to find video with Id=$videoId")
        }
      }

  override def deleteById(videoId: String, deleteVideoFile: Boolean)(block: Video => G[Unit]): F[Video] =
    transaction {
      findVideoById(videoId, None)
        .flatMap[(Video, Seq[Snapshot])] { video =>
          block(video)
            .productR(snapshotDao.findByVideo(videoId, None))
            .flatTap { snapshots =>
              snapshotDao
                .deleteByVideo(videoId)
                .productR { snapshots.traverse(snapshot => fileResourceDao.deleteById(snapshot.fileResource.id)) }
            }
            .productL(videoDao.deleteById(videoId))
            .productL(videoWatchHistoryDao.deleteBy(videoId))
            .productL(fileResourceDao.deleteById(video.fileResource.id))
            .map(snapshots => video -> snapshots)
        }
    }
      .flatMap {
        case (video, snapshots) =>
          snapshots
            .traverse(snapshot => repositoryService.delete(snapshot.fileResource.path))
            .productR {
              if (deleteVideoFile) repositoryService.delete(video.fileResource.path) else Applicative[F].pure(false)
            }
            .as(video)
      }

}
