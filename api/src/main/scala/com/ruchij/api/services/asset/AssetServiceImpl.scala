package com.ruchij.api.services.asset

import cats.data.OptionT
import cats.implicits._
import cats.{Applicative, ApplicativeError, MonadThrow, ~>}
import com.ruchij.api.daos.playlist.PlaylistDao
import com.ruchij.api.daos.user.models.User
import com.ruchij.api.services.asset.AssetService.FileByteRange
import com.ruchij.api.services.asset.models.Asset.FileRange
import com.ruchij.api.services.asset.models.AssetType.{AlbumArt, Snapshot, Thumbnail, Video}
import com.ruchij.api.services.asset.models.{Asset, AssetType}
import com.ruchij.core.daos.resource.FileResourceDao
import com.ruchij.core.daos.snapshot.SnapshotDao
import com.ruchij.core.daos.video.VideoDao
import com.ruchij.core.daos.videometadata.VideoMetadataDao
import com.ruchij.core.exceptions.ResourceNotFoundException
import com.ruchij.core.messaging.Publisher
import com.ruchij.core.messaging.models.VideoWatchMetric
import com.ruchij.core.services.repository.RepositoryService
import com.ruchij.core.types.Clock

import scala.reflect.ClassTag

class AssetServiceImpl[F[_]: MonadThrow: Clock, T[_]: MonadThrow](
  fileResourceDao: FileResourceDao[T],
  snapshotDao: SnapshotDao[T],
  videoDao: VideoDao[T],
  videoMetadataDao: VideoMetadataDao[T],
  playlistDao: PlaylistDao[T],
  repositoryService: RepositoryService[F],
  publisher: Publisher[F, VideoWatchMetric]
)(implicit transaction: T ~> F)
    extends AssetService[F] {

  private def typeValidator[A <: AssetType](
    validator: String => T[Boolean]
  )(implicit classTag: ClassTag[A]): String => T[Unit] =
    resourceId =>
      validator(resourceId).flatMap { isValid =>
        if (isValid) Applicative[T].unit
        else
          ApplicativeError[T, Throwable].raiseError[Unit] {
            ResourceNotFoundException(s"Unable to find ${classTag.getClass.getCanonicalName} file $resourceId")
          }
    }

  private def notFoundError[K[_]: ApplicativeError[*[_], Throwable], A <: AssetType](
    id: String
  )(implicit classTag: ClassTag[A]): K[Asset[F, A]] =
    ApplicativeError[K, Throwable].raiseError[Asset[F, A]] {
      ResourceNotFoundException(s"Unable to find ${classTag.getClass.getCanonicalName} file $id")
    }

  override def videoFile(
    id: String,
    user: User,
    maybeFileByteRange: Option[FileByteRange],
    maybeMaxStreamSize: Option[Long]
  ): F[Asset[F, Video.type]] =
    user.nonAdminUserId
      .fold(
        retrieve[Video.type](
          id = id,
          resourceTypeValidator = typeValidator[Video.type](videoDao.isVideoFileResourceExist),
          maybeFileByteRange = maybeFileByteRange,
          maybeMaxStreamSize = maybeMaxStreamSize
        )
      ) { userId =>
        transaction(videoDao.hasVideoFilePermission(id, userId)).flatMap { hasPermission =>
          if (hasPermission)
            retrieve[Video.type](
              id = id,
              maybeFileByteRange = maybeFileByteRange,
              maybeMaxStreamSize = maybeMaxStreamSize
            )
          else notFoundError[F, Video.type](id)
        }
      }
      .flatTap { asset =>
        Clock[F].timestamp.flatMap { timestamp =>
          val videoWatchMetric =
            VideoWatchMetric(user.id, asset.fileResource.id, asset.fileRange.start, asset.fileRange.end, timestamp)

          publisher.publishOne(videoWatchMetric)
        }
      }

  override def snapshot(id: String, user: User): F[Asset[F, Snapshot.type]] =
    user.nonAdminUserId.fold(
      retrieve[Snapshot.type](id, typeValidator[Snapshot.type](snapshotDao.isSnapshotFileResource))
    ) { userId =>
      transaction(snapshotDao.hasPermission(id, userId))
        .flatMap { hasPermission =>
          if (hasPermission)
            retrieve[Snapshot.type](id)
          else notFoundError[F, Snapshot.type](id)
        }
    }

  override def albumArt(id: String, user: User): F[Asset[F, AlbumArt.type]] =
    user.nonAdminUserId.fold(
      retrieve[AlbumArt.type ](id, typeValidator[AlbumArt.type ](playlistDao.isAlbumArtFileResource))
    ) { userId =>
      transaction(playlistDao.hasAlbumArtPermission(id, userId)).flatMap {
        hasPermission =>
          if (hasPermission)
            retrieve[AlbumArt.type ](id)
          else notFoundError[F, AlbumArt.type ](id)
      }
    }

  override def thumbnail(id: String): F[Asset[F, Thumbnail.type]] =
    retrieve[Thumbnail.type](id, typeValidator(videoMetadataDao.isThumbnailFileResource))

  private def retrieve[A <: AssetType](
    id: String,
    resourceTypeValidator: String => T[Unit] = _ => Applicative[T].unit,
    maybeFileByteRange: Option[FileByteRange] = None,
    maybeMaxStreamSize: Option[Long] = None
  ): F[Asset[F, A]] =
    OptionT {
      transaction {
        resourceTypeValidator(id).productR {
          fileResourceDao.getById(id)
        }
      }
    }.flatMap { fileResource =>
        {
          val end =
            Math.min(
              maybeFileByteRange.flatMap(_.end).getOrElse(fileResource.size),
              maybeFileByteRange
                .map(_.start)
                .flatMap(start => maybeMaxStreamSize.map(max => start + max))
                .orElse(maybeMaxStreamSize)
                .getOrElse(fileResource.size)
            )

          OptionT(repositoryService.read(fileResource.path, maybeFileByteRange.map(_.start), Some(end))).map { stream =>
            Asset[F, A](
              fileResource,
              stream.limit(maybeMaxStreamSize.getOrElse(fileResource.size)),
              FileRange(maybeFileByteRange.map(_.start).getOrElse(0), end)
            )
          }
        }
      }
      .getOrElseF {
        ApplicativeError[F, Throwable].raiseError(ResourceNotFoundException("Asset not found"))
      }

}
