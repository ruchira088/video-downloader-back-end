package com.ruchij.api.services.asset

import cats.data.OptionT
import cats.implicits._
import cats.{ApplicativeError, MonadThrow, ~>}
import com.ruchij.api.daos.user.models.User
import com.ruchij.api.services.asset.AssetService.FileByteRange
import com.ruchij.api.services.asset.models.Asset
import com.ruchij.api.services.asset.models.Asset.FileRange
import com.ruchij.core.daos.resource.FileResourceDao
import com.ruchij.core.daos.snapshot.SnapshotDao
import com.ruchij.core.daos.video.VideoDao
import com.ruchij.core.exceptions.ResourceNotFoundException
import com.ruchij.core.messaging.Publisher
import com.ruchij.core.messaging.models.VideoWatchMetric
import com.ruchij.core.services.repository.RepositoryService
import com.ruchij.core.types.JodaClock

class AssetServiceImpl[F[_]: MonadThrow: JodaClock, T[_]](
  fileResourceDao: FileResourceDao[T],
  snapshotDao: SnapshotDao[T],
  videoDao: VideoDao[T],
  repositoryService: RepositoryService[F],
  publisher: Publisher[F, VideoWatchMetric]
)(implicit transaction: T ~> F)
    extends AssetService[F] {

  override def videoFile(
    id: String,
    user: User,
    maybeFileByteRange: Option[FileByteRange],
    maybeMaxStreamSize: Option[Long]
  ): F[Asset[F]] =
    user.nonAdminUserId
      .fold(retrieve(id, maybeFileByteRange, maybeMaxStreamSize)) { userId =>
        transaction(videoDao.hasVideoFilePermission(id, userId)).flatMap { hasPermission =>
          if (hasPermission) retrieve(id, maybeFileByteRange, maybeMaxStreamSize)
          else
            ApplicativeError[F, Throwable].raiseError { ResourceNotFoundException(s"Unable to find video file $id") }
        }
      }
      .flatTap { asset =>
        JodaClock[F].timestamp.flatMap { timestamp =>
          val videoWatchMetric =
            VideoWatchMetric(user.id, asset.fileResource.id, asset.fileRange.start, asset.fileRange.end, timestamp)

          publisher.publishOne(videoWatchMetric)
        }
      }

  override def snapshot(id: String, user: User): F[Asset[F]] =
    user.nonAdminUserId.fold(retrieve(id, None, None)) { userId =>
      transaction(snapshotDao.hasPermission(id, userId))
        .flatMap { hasPermission =>
          if (hasPermission) retrieve(id, None, None)
          else ApplicativeError[F, Throwable].raiseError { ResourceNotFoundException(s"Unable to find snapshot: $id") }
        }
    }

  override def thumbnail(id: String): F[Asset[F]] = retrieve(id, None, None)

  private def retrieve(
    id: String,
    maybeFileByteRange: Option[FileByteRange],
    maybeMaxStreamSize: Option[Long]
  ): F[Asset[F]] =
    OptionT(transaction(fileResourceDao.getById(id)))
      .flatMap { fileResource => {
        val end =
          Math.min(
            maybeFileByteRange.flatMap(_.end).getOrElse(fileResource.size),
            maybeFileByteRange.map(_.start)
              .flatMap(start => maybeMaxStreamSize.map(max => start + max))
              .orElse(maybeMaxStreamSize)
              .getOrElse(fileResource.size)
          )

        OptionT(
          repositoryService.read(fileResource.path, maybeFileByteRange.map(_.start), Some(end))
        ).map { stream =>
          Asset[F](
            fileResource,
            stream.limit(maybeMaxStreamSize.getOrElse(fileResource.size)),
            FileRange(
              maybeFileByteRange.map(_.start).getOrElse(0),
              end
            )
          )
        }
      }
      }
      .getOrElseF {
        ApplicativeError[F, Throwable].raiseError(ResourceNotFoundException("Asset not found"))
      }

}
