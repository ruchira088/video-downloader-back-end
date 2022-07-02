package com.ruchij.core.services.asset

import cats.data.OptionT
import cats.implicits._
import cats.{ApplicativeError, MonadThrow, ~>}
import com.ruchij.core.daos.resource.FileResourceDao
import com.ruchij.core.daos.snapshot.SnapshotDao
import com.ruchij.core.daos.video.VideoDao
import com.ruchij.core.exceptions.ResourceNotFoundException
import com.ruchij.core.services.asset.AssetService.FileByteRange
import com.ruchij.core.services.asset.models.Asset
import com.ruchij.core.services.asset.models.Asset.FileRange
import com.ruchij.core.services.repository.RepositoryService

class AssetServiceImpl[F[_]: MonadThrow, T[_]](
  fileResourceDao: FileResourceDao[T],
  snapshotDao: SnapshotDao[T],
  videoDao: VideoDao[T],
  repositoryService: RepositoryService[F]
)(implicit transaction: T ~> F)
    extends AssetService[F] {

  override def videoFile(id: String, maybeUserId: Option[String], maybeFileByteRange: Option[FileByteRange]): F[Asset[F]] =
    maybeUserId.fold(retrieve(id, maybeFileByteRange)) { userId =>
      transaction(videoDao.hasVideoFilePermission(id, userId)).flatMap {
        hasPermission =>
          if (hasPermission) retrieve(id, maybeFileByteRange) else
          ApplicativeError[F, Throwable].raiseError { ResourceNotFoundException(s"Unable to find video file $id") }
      }
    }

  override def snapshot(id: String, maybeUserId: Option[String]): F[Asset[F]] =
    maybeUserId.fold(retrieve(id, None)) { userId =>
      transaction(snapshotDao.hasPermission(id, userId))
        .flatMap {
          hasPermission =>
            if (hasPermission) retrieve(id, None)
            else ApplicativeError[F, Throwable].raiseError { ResourceNotFoundException(s"Unable to find snapshot: $id") }
        }
    }

  override def thumbnail(id: String): F[Asset[F]] = retrieve(id, None)

  private def retrieve(id: String, maybeFileByteRange: Option[FileByteRange]): F[Asset[F]] =
    OptionT(transaction(fileResourceDao.getById(id)))
      .flatMap { fileResource =>
        OptionT(repositoryService.read(fileResource.path, maybeFileByteRange.map(_.start), maybeFileByteRange.flatMap(_.end)))
          .map { stream =>
            Asset[F](
              fileResource,
              stream,
              FileRange(
                maybeFileByteRange.map(_.start).getOrElse(0),
                maybeFileByteRange.flatMap(_.end).getOrElse(fileResource.size)
              )
            )
          }
      }
      .getOrElseF {
        ApplicativeError[F, Throwable].raiseError(ResourceNotFoundException("Asset not found"))
      }

}
