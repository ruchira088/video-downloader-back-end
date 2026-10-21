package com.ruchij.api.daos.playlist

import cats.{Applicative, ApplicativeError}
import cats.data.NonEmptyList
import cats.implicits._
import com.ruchij.api.daos.playlist.models.{Playlist, PlaylistSortBy}
import com.ruchij.core.daos.doobie.DoobieCustomMappings._
import com.ruchij.core.daos.doobie.DoobieUtils.ordering
import com.ruchij.core.daos.resource.FileResourceDao
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.video.VideoDao
import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.exceptions.ResourceNotFoundException
import com.ruchij.core.services.models.Order
import doobie.free.connection.ConnectionIO
import doobie.generic.auto._
import doobie.implicits.toSqlInterpolator
import doobie.util.fragment.Fragment
import doobie.util.fragments.{in, set, whereAndOpt}
import java.time.Instant

class DoobiePlaylistDao(fileResourceDao: FileResourceDao[ConnectionIO], videoDao: VideoDao[ConnectionIO])
    extends PlaylistDao[ConnectionIO] {
  import DoobiePlaylistDao.PlaylistRow

  override def insert(playlist: Playlist): ConnectionIO[Int] =
    sql"""
        INSERT INTO playlist (id, user_id, created_at, title, description, album_art_id)
            VALUES(
                ${playlist.id},
                ${playlist.userId},
                ${playlist.createdAt},
                ${playlist.title},
                ${playlist.description},
                ${playlist.albumArt.map(_.id)}
            )
    """.update.run
      .product(insertPlaylistVideos(playlist.id, playlist.videos.map(_.videoMetadata.id)))
      .map {
        case (playlistCount, videoCount) => playlistCount + videoCount
      }

  /** Inserts the videos in the given order, keeping only the first occurrence of a repeated video ID. */
  private def insertPlaylistVideos(playlistId: String, videoIds: Seq[String]): ConnectionIO[Int] =
    videoIds.distinct.zipWithIndex.toList
      .traverse {
        case (videoId, position) =>
          sql"""
            INSERT INTO playlist_video (playlist_id, video_id, position_index) VALUES ($playlistId, $videoId, $position)
          """.update.run
      }
      .map(_.sum)

  private def isPlaylistOwner(playlistId: String, maybeUserId: Option[String]): ConnectionIO[Boolean] =
    (fr"SELECT EXISTS(SELECT 1 FROM playlist" ++
      whereAndOpt(Some(fr"id = $playlistId"), maybeUserId.map(userId => fr"user_id = $userId")) ++ fr")")
      .query[Boolean]
      .unique

  /**
    * Fails with a ResourceNotFoundException unless every video exists and, when a user ID is given, the user has
    * permission for it.
    */
  private def checkVideoAccess(videoIds: Seq[String], maybeUserId: Option[String]): ConnectionIO[Unit] =
    NonEmptyList.fromList(videoIds.distinct.toList).fold(Applicative[ConnectionIO].unit) { videoIdList =>
      (fr"SELECT video.video_metadata_id FROM video" ++
        maybeUserId.fold(Fragment.empty) { userId =>
          fr"INNER JOIN permission ON permission.video_id = video.video_metadata_id AND permission.user_id = $userId"
        } ++
        fr"WHERE" ++ in(fr"video.video_metadata_id", videoIdList))
        .query[String]
        .to[Set]
        .flatMap { accessibleVideoIds =>
          val inaccessibleVideoIds = videoIdList.filterNot(accessibleVideoIds.contains)

          if (inaccessibleVideoIds.isEmpty) Applicative[ConnectionIO].unit
          else
            ApplicativeError[ConnectionIO, Throwable].raiseError {
              ResourceNotFoundException(s"Unable to find videos with IDs: ${inaccessibleVideoIds.mkString(", ")}")
            }
        }
    }

  override def update(
    playlistId: String,
    maybeTitle: Option[String],
    maybeDescription: Option[String],
    maybeVideoIds: Option[Seq[String]],
    maybeAlbumArt: Option[Either[Unit, String]],
    maybeUserId: Option[String]
  ): ConnectionIO[Int] = {
    val playlistTableUpdate: ConnectionIO[Int] =
      NonEmptyList
        .fromList {
          List(
            maybeTitle.map(title => fr"title = $title"),
            maybeDescription.map(description => fr"description = $description"),
            maybeAlbumArt.map(
              _.fold(_ => fr"album_art_id = NULL", fileResourceId => fr"album_art_id = $fileResourceId")
            )
          ).flatMap(_.toList)
        }
        .fold(Applicative[ConnectionIO].pure(0)) { setValues =>
          (fr"UPDATE playlist" ++ set(setValues) ++
            whereAndOpt(Some(fr"id = $playlistId"), maybeUserId.map(userId => fr"user_id = $userId"))).update.run
        }

    val playlistVideosUpdate: ConnectionIO[Int] =
      maybeVideoIds.fold(Applicative[ConnectionIO].pure(0)) { videoIds =>
        checkVideoAccess(videoIds, maybeUserId)
          .productR(sql"DELETE FROM playlist_video WHERE playlist_id = $playlistId".update.run)
          .product(insertPlaylistVideos(playlistId, videoIds))
          .map { case (deletions, additions) => deletions + additions }
      }

    // Nothing is changed unless the playlist exists and, when a user ID is given, belongs to that user
    isPlaylistOwner(playlistId, maybeUserId).flatMap { isOwner =>
      if (isOwner)
        playlistTableUpdate
          .product(playlistVideosUpdate)
          .map { case (playlistUpdates, playlistVideoUpdates) => playlistUpdates + playlistVideoUpdates }
      else Applicative[ConnectionIO].pure(0)
    }
  }

  override def findById(playlistId: String, maybeUserId: Option[String]): ConnectionIO[Option[Playlist]] =
    findPlaylists(
      whereAndOpt(Some(fr"playlist.id = $playlistId"), maybeUserId.map(userId => fr"playlist.user_id = $userId")),
      maybeUserId
    ).map(_.headOption)

  override def search(
    maybeSearchTerm: Option[String],
    pageSize: Int,
    pageNumber: Int,
    order: Order,
    sortBy: PlaylistSortBy,
    maybeUserId: Option[String]
  ): ConnectionIO[Seq[Playlist]] =
    findPlaylists(
      whereAndOpt(
        maybeSearchTerm.map(
          searchTerm =>
            fr"(playlist.title ILIKE ${"%" + searchTerm + "%"} OR playlist.description ILIKE ${"%" + searchTerm + "%"})"
        ),
        maybeUserId.map(userId => fr"playlist.user_id = $userId")
      ) ++
        fr"ORDER BY playlist." ++ sortBy.fragment ++ ordering(order) ++
        fr"LIMIT $pageSize OFFSET ${pageNumber * pageSize}",
      maybeUserId
    ).widen[Seq[Playlist]]

  /**
    * Loads a set of playlists with three queries in total: the playlist rows (with their album art joined in), the
    * playlist-to-video links, and the videos themselves. Videos are returned in their playlist order and, when a user
    * ID is given, only the videos that user has permission for are included.
    */
  private def findPlaylists(filter: Fragment, maybeUserId: Option[String]): ConnectionIO[List[Playlist]] =
    (fr"""
      SELECT
        playlist.id, playlist.user_id, playlist.created_at, playlist.title, playlist.description,
        file_resource.id, file_resource.created_at, file_resource.path, file_resource.media_type, file_resource.size
      FROM playlist
      LEFT JOIN file_resource ON playlist.album_art_id = file_resource.id
    """ ++ filter)
      .query[PlaylistRow]
      .to[List]
      .flatMap { rows =>
        NonEmptyList.fromList(rows.map(_.id)).fold(Applicative[ConnectionIO].pure(rows.map(_.toPlaylist(Nil)))) {
          playlistIds =>
            (fr"SELECT playlist_video.playlist_id, playlist_video.video_id FROM playlist_video" ++
              maybeUserId.fold(Fragment.empty) { userId =>
                fr"""
                  INNER JOIN permission
                    ON permission.video_id = playlist_video.video_id AND permission.user_id = $userId
                """
              } ++
              fr"WHERE" ++ in(fr"playlist_video.playlist_id", playlistIds) ++
              fr"ORDER BY playlist_video.playlist_id, playlist_video.position_index")
              .query[(String, String)]
              .to[List]
              .flatMap { playlistVideos =>
                NonEmptyList
                  .fromList(playlistVideos.map { case (_, videoId) => videoId }.distinct)
                  .fold(Applicative[ConnectionIO].pure(Seq.empty[Video]))(videoDao.findByIds)
                  .map { videos =>
                    val videosById = videos.map(video => video.id -> video).toMap
                    val videosByPlaylist = playlistVideos.groupMap { case (playlistId, _) => playlistId } {
                      case (_, videoId) => videoId
                    }

                    rows.map { row =>
                      row.toPlaylist(videosByPlaylist.getOrElse(row.id, Nil).flatMap(videosById.get))
                    }
                  }
              }
        }
      }

  override def isAlbumArtFileResource(fileResourceId: String): ConnectionIO[Boolean] =
    sql"SELECT EXISTS(SELECT 1 FROM playlist WHERE album_art_id = $fileResourceId)".query[Boolean].unique

  override def hasAlbumArtPermission(fileResourceId: String, userId: String): ConnectionIO[Boolean] =
    sql"SELECT EXISTS(SELECT 1 FROM playlist WHERE album_art_id = $fileResourceId AND user_id = $userId)".query[Boolean].unique

  override def deleteById(playlistId: String, maybeUserId: Option[String]): ConnectionIO[Int] =
    maybeUserId
      .fold[ConnectionIO[Boolean]](Applicative[ConnectionIO].pure(true)) { userId =>
        sql"SELECT EXISTS(SELECT 1 FROM playlist WHERE id = $playlistId AND user_id = $userId)"
          .query[Boolean]
          .unique
      }
      .flatMap { isOwner =>
        if (isOwner) {
          sql"DELETE FROM playlist_video WHERE playlist_id = $playlistId".update.run
            .product {
              sql"DELETE FROM playlist WHERE id = $playlistId".update.run
            }
            .map {
              case (playlistVideoDeletions, playlistDeletion) => playlistVideoDeletions + playlistDeletion
            }
        } else Applicative[ConnectionIO].pure(0)
      }

}

object DoobiePlaylistDao {
  private final case class PlaylistRow(
    id: String,
    userId: String,
    createdAt: Instant,
    title: String,
    description: Option[String],
    albumArt: Option[FileResource]
  ) {
    def toPlaylist(videos: Seq[Video]): Playlist = Playlist(id, userId, createdAt, title, description, videos, albumArt)
  }
}
