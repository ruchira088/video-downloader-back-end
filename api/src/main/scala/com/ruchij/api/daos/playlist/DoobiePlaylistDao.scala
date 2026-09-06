package com.ruchij.api.daos.playlist

import cats.Applicative
import cats.data.NonEmptyList
import cats.implicits._
import com.ruchij.api.daos.playlist.models.{Playlist, PlaylistSortBy}
import com.ruchij.core.daos.doobie.DoobieCustomMappings._
import com.ruchij.core.daos.doobie.DoobieUtils.ordering
import com.ruchij.core.daos.resource.FileResourceDao
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.video.VideoDao
import com.ruchij.core.daos.video.models.Video
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
      .product {
        playlist.videos
          .map(_.videoMetadata.id)
          .traverse { videoId =>
            sql"INSERT INTO playlist_video (playlist_id, video_id) VALUES (${playlist.id}, $videoId)".update.run
          }
      }
      .map {
        case (playlistCount, videoCount) => playlistCount + videoCount.sum
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

    playlistTableUpdate
      .product {
        maybeVideoIds.fold(Applicative[ConnectionIO].pure(0)) { videoIds =>
          sql"DELETE FROM playlist_video WHERE playlist_id = $playlistId".update.run
            .product {
              videoIds.traverse { videoId =>
                sql"INSERT INTO playlist_video (playlist_id, video_id) VALUES ($playlistId, $videoId)".update.run
              }
            }
            .map { case (deletions, additions) => deletions + additions.sum }
        }
      }
      .map { case (playlistUpdates, playlistVideoUpdates) => playlistUpdates + playlistVideoUpdates }
  }

  override def findById(playlistId: String, maybeUserId: Option[String]): ConnectionIO[Option[Playlist]] =
    findPlaylists(
      whereAndOpt(Some(fr"playlist.id = $playlistId"), maybeUserId.map(userId => fr"playlist.user_id = $userId"))
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
        fr"LIMIT $pageSize OFFSET ${pageNumber * pageSize}"
    ).widen[Seq[Playlist]]

  /**
    * Loads a set of playlists with three queries in total: the playlist rows (with their album art joined in), the
    * playlist-to-video links, and the videos themselves.
    */
  private def findPlaylists(filter: Fragment): ConnectionIO[List[Playlist]] =
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
            (fr"SELECT playlist_id, video_id FROM playlist_video WHERE" ++ in(fr"playlist_id", playlistIds))
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
