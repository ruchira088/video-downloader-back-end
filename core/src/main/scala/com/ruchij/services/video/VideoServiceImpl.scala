package com.ruchij.services.video

import cats.MonadError
import cats.data.OptionT
import cats.implicits._
import com.ruchij.daos.resource.models.FileResource
import com.ruchij.daos.video.VideoDao
import com.ruchij.daos.video.models.Video
import com.ruchij.exceptions.ResourceNotFoundException

class VideoServiceImpl[F[_]: MonadError[*[_], Throwable]](videoDao: VideoDao[F]) extends VideoService[F] {

  override def insert(videoMetadataKey: String, fileResource: FileResource): F[Video] =
    videoDao
      .insert(videoMetadataKey, fileResource)
      .productR(fetchById(videoMetadataKey))

  override def fetchById(id: String): F[Video] =
    OptionT(videoDao.findById(id))
      .getOrElseF {
        MonadError[F, Throwable].raiseError(ResourceNotFoundException(s"Unable to find video with ID: $id"))
      }

  override def search(term: Option[String], pageNumber: Int, pageSize: Int): F[Seq[Video]] =
    videoDao.search(term, pageNumber, pageSize)
}
