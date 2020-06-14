package com.ruchij.services.video

import cats.{Monad, MonadError, ~>}
import cats.data.OptionT
import cats.implicits._
import com.ruchij.daos.resource.FileResourceDao
import com.ruchij.daos.resource.models.FileResource
import com.ruchij.daos.snapshot.SnapshotDao
import com.ruchij.daos.snapshot.models.Snapshot
import com.ruchij.daos.video.VideoDao
import com.ruchij.daos.video.models.Video
import com.ruchij.exceptions.ResourceNotFoundException

class VideoServiceImpl[F[_]: MonadError[*[_], Throwable], T[_]: Monad](
  videoDao: VideoDao[T],
  snapshotDao: SnapshotDao[T],
  fileResourceDao: FileResourceDao[T]
)(implicit transaction: T ~> F)
    extends VideoService[F] {

  override def insert(videoMetadataKey: String, fileResource: FileResource): F[Video] =
    transaction {
      fileResourceDao
        .insert(fileResource)
        .productR(videoDao.insert(videoMetadataKey, fileResource.id))
    }.productR(fetchById(videoMetadataKey))

  override def fetchById(videoId: String): F[Video] =
    OptionT(transaction(videoDao.findById(videoId)))
      .getOrElseF {
        MonadError[F, Throwable].raiseError(ResourceNotFoundException(s"Unable to find video with ID: $videoId"))
      }

  override def search(term: Option[String], pageNumber: Int, pageSize: Int): F[Seq[Video]] =
    transaction {
      videoDao.search(term, pageNumber, pageSize)
    }

  override def fetchVideoSnapshots(videoId: String): F[Seq[Snapshot]] =
    transaction {
      snapshotDao.findByVideo(videoId)
    }

}
