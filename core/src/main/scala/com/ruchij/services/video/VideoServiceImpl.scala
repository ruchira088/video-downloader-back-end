package com.ruchij.services.video

import java.util.concurrent.TimeUnit

import cats.MonadError
import cats.data.OptionT
import cats.effect.Clock
import cats.implicits._
import com.ruchij.daos.video.VideoDao
import com.ruchij.daos.video.models.Video
import com.ruchij.daos.videometadata.models.VideoMetadata
import com.ruchij.exceptions.ResourceNotFoundException
import com.ruchij.services.repository.RepositoryService
import fs2.Stream
import org.joda.time.DateTime

class VideoServiceImpl[F[_]: MonadError[*[_], Throwable]: Clock](
  videoDao: VideoDao[F],
  repositoryService: RepositoryService[F]
) extends VideoService[F] {

  override def insert(videoMetadata: VideoMetadata, path: String): F[Video] =
    Clock[F].realTime(TimeUnit.MILLISECONDS).flatMap { timestamp =>
      val video = Video(new DateTime(timestamp), videoMetadata, path)

      videoDao.insert(video).as(video)
    }

  override def fetchByKey(key: String): F[Video] =
    OptionT(videoDao.findByKey(key))
      .getOrElseF {
        MonadError[F, Throwable].raiseError(ResourceNotFoundException(s"Unable to find video with key: $key"))
      }

  override def fetchResourceByVideoKey(key: String, start: Option[Long], end: Option[Long]): F[(Video, Stream[F, Byte])] =
    fetchByKey(key).flatMap { video =>
      OptionT(repositoryService.read(video.path, start, end))
        .getOrElseF {
          MonadError[F, Throwable].raiseError(ResourceNotFoundException(s"Unable to find video file for video with key: $key"))
        }
        .map(video -> _)
    }

  override def search(term: Option[String], pageNumber: Int, pageSize: Int): F[Seq[Video]] =
    videoDao.search(term, pageNumber, pageSize)
}
