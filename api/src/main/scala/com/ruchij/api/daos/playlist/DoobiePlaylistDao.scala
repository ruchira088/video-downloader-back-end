package com.ruchij.api.daos.playlist

import cats.Applicative
import cats.data.OptionT
import cats.implicits._
import com.ruchij.api.daos.playlist.models.{Playlist, PlaylistSortBy}
import com.ruchij.core.daos.doobie.DoobieCustomMappings._
import com.ruchij.core.daos.doobie.DoobieUtils.ordering
import com.ruchij.core.daos.resource.FileResourceDao
import com.ruchij.core.daos.video.VideoDao
import com.ruchij.core.services.models.Order
import doobie.free.connection.ConnectionIO
import doobie.implicits.toSqlInterpolator
import doobie.util.fragments.{setOpt, whereAndOpt}
import org.joda.time.DateTime

class DoobiePlaylistDao(fileResourceDao: FileResourceDao[ConnectionIO], videoDao: VideoDao[ConnectionIO])
    extends PlaylistDao[ConnectionIO] {

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
    val playlistTableUpdate =
      if (List(maybeTitle, maybeDescription, maybeAlbumArt).exists(_.nonEmpty))
        (fr"UPDATE playlist" ++
          setOpt(
            maybeTitle.map(title => fr"title = $title"),
            maybeDescription.map(description => fr"description = $description"),
            maybeAlbumArt.map(_.fold(_ => fr"album_art_id = NULL", fileResourceId => fr"album_art_id = $fileResourceId"))
          ) ++ fr"WHERE id = $playlistId").update.run
      else Applicative[ConnectionIO].pure(0)

    playlistTableUpdate.product {
        maybeVideoIds.fold(Applicative[ConnectionIO].pure(0)) {
          videoIds =>
            sql"DELETE FROM playlist_video WHERE playlist_id = $playlistId".update.run
              .product {
                videoIds.traverse { videoId =>
                  sql"INSERT INTO playlist_video (playlist_id, video_id) VALUES ($playlistId, $videoId)"
                    .update
                    .run
                }
              }
              .map { case (deletions, additions) => deletions + additions.sum }
        }
      }
      .map { case (playlistUpdates, playlistVideoUpdates)  => playlistUpdates + playlistVideoUpdates }
  }

  override def findById(playlistId: String, maybeUserId: Option[String]): ConnectionIO[Option[Playlist]] =
    OptionT {
      sql"SELECT id, user_id, created_at, title, description, album_art_id FROM playlist WHERE id = $playlistId"
        .query[(String, String, DateTime, String, Option[String], Option[String])]
        .option
    }
      .product {
        OptionT.liftF {
          sql"SELECT video_id FROM playlist_video WHERE playlist_id = $playlistId"
            .query[String]
            .to[List]
            .flatMap { videoIds =>
              videoIds
                .traverse { videoId => videoDao.findById(videoId, None) }
                .map { videos => videos.collect { case Some(video) => video }}
            }
        }
      }
      .semiflatMap {
        case ((id, userId, createdAt, title, maybeDescription, maybeAlbumArt), videos) =>
          OptionT.fromOption[ConnectionIO](maybeAlbumArt)
            .flatMapF(albumArt => fileResourceDao.getById(albumArt))
            .map { fileResource => Playlist(id, userId, createdAt, title, maybeDescription, videos, Some(fileResource)) }
            .getOrElse {
              Playlist(id, userId, createdAt, title, maybeDescription, videos, None)
            }
      }
      .value

  override def search(
    maybeSearchTerm: Option[String],
    pageSize: Int,
    pageNumber: Int,
    order: Order,
    sortBy: PlaylistSortBy,
    maybeUserId: Option[String]
  ): ConnectionIO[Seq[Playlist]] =
    (fr"SELECT id FROM playlist" ++
      whereAndOpt(maybeSearchTerm.map(searchTerm => fr"title ILIKE ${"%" + searchTerm + "%"} OR description ILIKE ${"%" + searchTerm + "%"}")) ++
      fr"ORDER BY" ++
      sortBy.fragment ++
      ordering(order) ++
      fr"LIMIT $pageSize OFFSET ${pageNumber * pageSize}")
      .query[String]
      .to[List]
      .flatMap {
        playlistIds =>
          playlistIds
            .traverse { playlistId => findById(playlistId, maybeUserId) }
            .map(_.flattenOption)
      }

  override def deleteById(playlistId: String, maybeUserId: Option[String]): ConnectionIO[Int] =
    sql"DELETE FROM playlist_video WHERE playlist_id = $playlistId"
      .update
      .run
      .product {
        sql"DELETE FROM playlist WHERE id = $playlistId".update.run
      }
      .map {
        case (playlistVideoDeletions, playlistDeletion) => playlistVideoDeletions + playlistDeletion
      }

}
