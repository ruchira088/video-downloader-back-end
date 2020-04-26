package com.ruchij.services.video

import cats.MonadError
import cats.data.OptionT
import cats.implicits._
import com.ruchij.daos.resource.models.FileResource
import com.ruchij.daos.video.VideoDao
import com.ruchij.daos.video.models.Video
import com.ruchij.exceptions.ResourceNotFoundException
import com.ruchij.services.repository.RepositoryService
import fs2.Stream

class VideoServiceImpl[F[_]: MonadError[*[_], Throwable]](
  videoDao: VideoDao[F],
  repositoryService: RepositoryService[F]
) extends VideoService[F] {

  override def insert(videoMetadataKey: String, fileResource: FileResource): F[Video] =
    videoDao
      .insert(videoMetadataKey, fileResource)
      .productR(fetchByKey(videoMetadataKey))

  override def fetchByKey(key: String): F[Video] =
    OptionT(videoDao.findByKey(key))
      .getOrElseF {
        MonadError[F, Throwable].raiseError(ResourceNotFoundException(s"Unable to find video with key: $key"))
      }

  override def fetchResourceByVideoKey(
    key: String,
    start: Option[Long],
    end: Option[Long]
  ): F[(Video, Stream[F, Byte])] =
    fetchByKey(key).flatMap { video =>
      OptionT(repositoryService.read(video.fileResource.path, start, end.orElse(Some(video.videoMetadata.size))))
        .getOrElseF {
          MonadError[F, Throwable].raiseError(
            ResourceNotFoundException(s"Unable to find video file for video with key: $key")
          )
        }
        .map(video -> _)
    }

  override def search(term: Option[String], pageNumber: Int, pageSize: Int): F[Seq[Video]] =
    videoDao.search(term, pageNumber, pageSize)
}
