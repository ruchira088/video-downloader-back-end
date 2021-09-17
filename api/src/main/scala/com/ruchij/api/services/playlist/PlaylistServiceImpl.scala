package com.ruchij.api.services.playlist

import cats.data.OptionT
import cats.effect.{Clock, Sync}
import cats.implicits._
import cats.{ApplicativeError, MonadError, ~>}
import com.ruchij.api.daos.PlaylistDao
import com.ruchij.api.daos.models.{Playlist, PlaylistSortBy}
import com.ruchij.core.config.StorageConfiguration
import com.ruchij.core.daos.doobie.DoobieUtils.SingleUpdateOps
import com.ruchij.core.daos.resource.FileResourceDao
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.exceptions.{InvalidConditionException, ResourceNotFoundException}
import com.ruchij.core.services.models.Order
import com.ruchij.core.services.repository.RepositoryService
import com.ruchij.core.types.{JodaClock, RandomGenerator}
import fs2.Stream
import org.http4s.MediaType

import java.util.UUID

class PlaylistServiceImpl[F[+ _]: Sync: Clock: RandomGenerator[*[_], UUID], G[_]: MonadError[*[_], Throwable]](
  playlistDao: PlaylistDao[G],
  fileResourceDao: FileResourceDao[G],
  repositoryService: RepositoryService[F],
  storageConfiguration: StorageConfiguration
)(implicit transaction: G ~> F)
    extends PlaylistService[F] {

  override def create(title: String, description: Option[String]): F[Playlist] =
    for {
      id <- RandomGenerator[F, UUID].generate
      timestamp <- JodaClock[F].timestamp
      playlist = Playlist(id.toString, timestamp, title, description, Seq.empty, None)
      _ <- transaction(playlistDao.insert(playlist).one)
    } yield playlist

  override def updatePlaylist(
    playlistId: String,
    maybeTitle: Option[String],
    maybeDescription: Option[String],
    maybeVideoIdList: Option[Seq[String]]
  ): F[Playlist] =
    OptionT {
      transaction {
        playlistDao
          .update(playlistId, maybeTitle, maybeDescription, maybeVideoIdList, None)
          .productR(playlistDao.findById(playlistId))
      }
    }.getOrElseF(playlistNotFound(playlistId))

  override def fetchById(playlistId: String): F[Playlist] =
    OptionT(transaction(playlistDao.findById(playlistId))).getOrElseF(playlistNotFound(playlistId))

  override def search(
    maybeSearchTerm: Option[String],
    pageSize: Int,
    pageNumber: Int,
    order: Order,
    playlistSortBy: PlaylistSortBy
  ): F[Seq[Playlist]] =
    transaction {
      playlistDao.search(maybeSearchTerm, pageSize, pageNumber, order, playlistSortBy)
    }

  override def addAlbumArt(
    playlistId: String,
    fileName: String,
    mediaType: MediaType,
    data: Stream[F, Byte]
  ): F[Playlist] =
    for {
      timestamp <- JodaClock[F].timestamp
      id <- RandomGenerator[F, UUID].generate.map(_.toString)
      fileKey = s"${storageConfiguration.imageFolder}/album-art/$playlistId/${id.take(8)}-$fileName"

      _ <- repositoryService.write(fileKey, data).compile.drain
      fileSize <- OptionT(repositoryService.size(fileKey))
        .getOrElseF(ApplicativeError[F, Throwable].raiseError(InvalidConditionException("Unable to find saved file")))
      fileResource = FileResource(id, timestamp, fileKey, mediaType, fileSize)

      maybePlaylist <- transaction {
        fileResourceDao
          .insert(fileResource)
          .productR { playlistDao.update(playlistId, None, None, None, Some(Right(fileResource.id))).singleUpdate.value }
          .productR { playlistDao.findById(playlistId) }
      }

      playlist <- OptionT.fromOption[F](maybePlaylist).getOrElseF(playlistNotFound(playlistId))
    } yield playlist

  override def removeAlbumArt(playlistId: String): F[Playlist] =
    transaction {
      playlistDao
        .update(playlistId, None, None, None, Some(Left((): Unit)))
        .singleUpdate
        .value
        .productR(playlistDao.findById(playlistId))
    }.flatMap { maybePlaylist =>
        OptionT.fromOption[F](maybePlaylist).getOrElseF(playlistNotFound(playlistId))
      }

  override def deletePlaylist(playlistId: String): F[Playlist] =
    transaction {
      playlistDao
        .findById(playlistId)
        .productL(playlistDao.deleteById(playlistId))
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
