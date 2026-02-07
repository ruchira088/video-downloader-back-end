package com.ruchij.api.services.playlist

import cats.data.OptionT
import cats.effect.Sync
import cats.implicits._
import cats.{ApplicativeError, MonadThrow, ~>}
import com.ruchij.api.daos.playlist.PlaylistDao
import com.ruchij.api.daos.playlist.models.{Playlist, PlaylistSortBy}
import com.ruchij.core.config.StorageConfiguration
import com.ruchij.core.daos.doobie.DoobieUtils.SingleUpdateOps
import com.ruchij.core.daos.resource.FileResourceDao
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.exceptions.{InvalidConditionException, ResourceNotFoundException}
import com.ruchij.core.services.models.Order
import com.ruchij.core.services.repository.RepositoryService
import com.ruchij.core.types.{Clock, RandomGenerator}
import fs2.Stream
import org.http4s.MediaType

import java.util.UUID

class PlaylistServiceImpl[F[_]: Sync: Clock: RandomGenerator[*[_], UUID], G[_]: MonadThrow](
  playlistDao: PlaylistDao[G],
  fileResourceDao: FileResourceDao[G],
  repositoryService: RepositoryService[F],
  storageConfiguration: StorageConfiguration
)(implicit transaction: G ~> F)
    extends PlaylistService[F] {

  override def create(title: String, description: Option[String], userId: String): F[Playlist] =
    for {
      id <- RandomGenerator[F, UUID].generate
      timestamp <- Clock[F].timestamp
      playlist = Playlist(id.toString, userId, timestamp, title, description, Seq.empty, None)
      _ <- transaction(playlistDao.insert(playlist).one)
    } yield playlist

  override def updatePlaylist(
    playlistId: String,
    maybeTitle: Option[String],
    maybeDescription: Option[String],
    maybeVideoIdList: Option[Seq[String]],
    maybeUserId: Option[String]
  ): F[Playlist] =
    OptionT {
      transaction {
        playlistDao
          .update(playlistId, maybeTitle, maybeDescription, maybeVideoIdList, None, maybeUserId)
          .productR(playlistDao.findById(playlistId, maybeUserId))
      }
    }.getOrElseF(playlistNotFound(playlistId))

  override def fetchById(playlistId: String, maybeUserId: Option[String]): F[Playlist] =
    OptionT(transaction(playlistDao.findById(playlistId, maybeUserId))).getOrElseF(playlistNotFound(playlistId))

  override def search(
    maybeSearchTerm: Option[String],
    pageSize: Int,
    pageNumber: Int,
    order: Order,
    playlistSortBy: PlaylistSortBy,
    maybeUserId: Option[String]
  ): F[Seq[Playlist]] =
    transaction {
      playlistDao.search(maybeSearchTerm, pageSize, pageNumber, order, playlistSortBy, maybeUserId)
    }

  override def addAlbumArt(
    playlistId: String,
    fileName: String,
    mediaType: MediaType,
    data: Stream[F, Byte],
    maybeUserId: Option[String]
  ): F[Playlist] =
    for {
      timestamp <- Clock[F].timestamp
      id <- RandomGenerator[F, UUID].generate.map(_.toString)
      fileKey = s"${storageConfiguration.imageFolder}/${id.take(8)}-$fileName"

      _ <- repositoryService.write(fileKey, data).compile.drain
      fileSize <- OptionT(repositoryService.size(fileKey))
        .getOrElseF(ApplicativeError[F, Throwable].raiseError(InvalidConditionException("Unable to find saved file")))
      fileResource = FileResource(id, timestamp, fileKey, mediaType, fileSize)

      maybePlaylist <- transaction {
        fileResourceDao
          .insert(fileResource)
          .productR {
            playlistDao
              .update(playlistId, None, None, None, Some(Right(fileResource.id)), maybeUserId)
              .singleUpdate
              .productR(OptionT(playlistDao.findById(playlistId, maybeUserId)))
              .value
          }
      }

      playlist <- OptionT.fromOption[F](maybePlaylist).getOrElseF(playlistNotFound(playlistId))
    } yield playlist

  override def removeAlbumArt(playlistId: String, maybeUserId: Option[String]): F[Playlist] =
    transaction {
      playlistDao
        .update(playlistId, None, None, None, Some(Left((): Unit)), maybeUserId)
        .singleUpdate
        .value
        .productR(playlistDao.findById(playlistId, maybeUserId))
    }.flatMap { maybePlaylist =>
      OptionT.fromOption[F](maybePlaylist).getOrElseF(playlistNotFound(playlistId))
    }

  override def deletePlaylist(playlistId: String, maybeUserId: Option[String]): F[Playlist] =
    transaction {
      playlistDao
        .findById(playlistId, maybeUserId)
        .productL(playlistDao.deleteById(playlistId, maybeUserId))
    }.flatMap { maybePlaylist =>
      OptionT.fromOption[F](maybePlaylist).getOrElseF(playlistNotFound(playlistId))
    }

  private def playlistNotFound(playlistId: String): F[Playlist] =
    ApplicativeError[F, Throwable].raiseError {
      ResourceNotFoundException {
        s"Unable to find playlist with ID: $playlistId"
      }
    }
}
