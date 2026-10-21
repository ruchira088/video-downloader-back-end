package com.ruchij.api.services.playlist

import cats.data.OptionT
import cats.effect.Sync
import cats.implicits._
import cats.{Applicative, ApplicativeError, MonadThrow, ~>}
import com.ruchij.api.daos.playlist.PlaylistDao
import com.ruchij.api.daos.playlist.models.{Playlist, PlaylistSortBy}
import com.ruchij.core.config.StorageConfiguration
import com.ruchij.core.daos.doobie.DoobieUtils.SingleUpdateOps
import com.ruchij.core.daos.resource.FileResourceDao
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.exceptions.{InvalidConditionException, ResourceNotFoundException, ValidationException}
import com.ruchij.core.services.models.Order
import com.ruchij.core.services.repository.RepositoryService
import com.ruchij.core.types.{Clock, RandomGenerator}
import fs2.{Chunk, Pipe, Stream}
import org.http4s.MediaType

import java.time.Instant
import java.util.UUID

class PlaylistServiceImpl[F[_]: Sync: Clock: RandomGenerator[*[_], UUID], G[_]: MonadThrow](
  playlistDao: PlaylistDao[G],
  fileResourceDao: FileResourceDao[G],
  repositoryService: RepositoryService[F],
  storageConfiguration: StorageConfiguration,
  maxAlbumArtSize: Long = PlaylistService.MaxAlbumArtSize
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
    transaction {
      // Failing inside the transaction rolls it back, so a playlist the user does not own, or a video the user may
      // not add, leaves the playlist unchanged
      findPlaylist(playlistId, maybeUserId)
        .productR(playlistDao.update(playlistId, maybeTitle, maybeDescription, maybeVideoIdList, None, maybeUserId))
        .productR(findPlaylist(playlistId, maybeUserId))
    }

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
      _ <- validateAlbumArtMediaType(mediaType)

      // Ownership is checked before anything is written, so a user cannot store files against another's playlist
      _ <- fetchById(playlistId, maybeUserId)

      timestamp <- Clock[F].timestamp
      id <- RandomGenerator[F, UUID].generate.map(_.toString)
      fileKey = s"${storageConfiguration.imageFolder}/${id.take(8)}-$fileName"

      playlist <- saveAlbumArt(playlistId, id, timestamp, fileKey, mediaType, data, maybeUserId)
        .onError { _ =>
          // Removes a partially written file, or one that is not referenced because the update failed
          repositoryService.delete(fileKey).attempt.void
        }
    } yield playlist

  private def saveAlbumArt(
    playlistId: String,
    fileResourceId: String,
    timestamp: Instant,
    fileKey: String,
    mediaType: MediaType,
    data: Stream[F, Byte],
    maybeUserId: Option[String]
  ): F[Playlist] =
    for {
      _ <- repositoryService.write(fileKey, data.through(limitSize(maxAlbumArtSize))).compile.drain
      fileSize <- OptionT(repositoryService.size(fileKey))
        .getOrElseF(ApplicativeError[F, Throwable].raiseError(InvalidConditionException("Unable to find saved file")))
      fileResource = FileResource(fileResourceId, timestamp, fileKey, mediaType, fileSize)

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

  private def validateAlbumArtMediaType(mediaType: MediaType): F[Unit] =
    if (PlaylistService.AlbumArtMediaTypes.contains(mediaType)) Applicative[F].unit
    else
      ApplicativeError[F, Throwable].raiseError {
        val supportedTypes =
          PlaylistService.AlbumArtMediaTypes.map(supported => s"${supported.mainType}/${supported.subType}")

        ValidationException(
          s"Unsupported album art type: ${mediaType.mainType}/${mediaType.subType}. " +
            s"Supported types are ${supportedTypes.mkString(", ")}"
        )
      }

  /** Fails the stream with a ValidationException as soon as more than maxSize bytes have passed through */
  private def limitSize(maxSize: Long): Pipe[F, Byte, Byte] =
    _.chunks
      .evalMapAccumulate(0L) { (total, chunk) =>
        val updatedTotal = total + chunk.size

        if (updatedTotal > maxSize)
          ApplicativeError[F, Throwable].raiseError[(Long, Chunk[Byte])] {
            ValidationException(s"Album art exceeds the maximum size of $maxSize bytes")
          }
        else Applicative[F].pure(updatedTotal -> chunk)
      }
      .flatMap { case (_, chunk) => Stream.chunk(chunk) }

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

  private def findPlaylist(playlistId: String, maybeUserId: Option[String]): G[Playlist] =
    OptionT(playlistDao.findById(playlistId, maybeUserId)).getOrRaise(playlistNotFoundException(playlistId))

  private def playlistNotFoundException(playlistId: String): ResourceNotFoundException =
    ResourceNotFoundException(s"Unable to find playlist with ID: $playlistId")

  private def playlistNotFound(playlistId: String): F[Playlist] =
    ApplicativeError[F, Throwable].raiseError(playlistNotFoundException(playlistId))
}
