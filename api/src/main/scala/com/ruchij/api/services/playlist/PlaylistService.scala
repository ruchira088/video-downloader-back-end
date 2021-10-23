package com.ruchij.api.services.playlist

import com.ruchij.api.daos.playlist.models.{Playlist, PlaylistSortBy}
import com.ruchij.core.services.models.Order
import fs2.Stream
import org.http4s.MediaType

trait PlaylistService[F[_]] {
  def create(title: String, description: Option[String], userId: String): F[Playlist]

  def updatePlaylist(
    playlistId: String,
    maybeTitle: Option[String],
    maybeDescription: Option[String],
    maybeVideoIdList: Option[Seq[String]],
    maybeUserId: Option[String]
  ): F[Playlist]

  def fetchById(playlistId: String, maybeUserId: Option[String]): F[Playlist]

  def search(
    maybeSearchTerm: Option[String],
    pageSize: Int,
    pageNumber: Int,
    order: Order,
    playlistSortBy: PlaylistSortBy,
    maybeUserId: Option[String]
  ): F[Seq[Playlist]]

  def addAlbumArt(
    playlistId: String,
    fileName: String,
    mediaType: MediaType,
    data: Stream[F, Byte],
    maybeUserId: Option[String]
  ): F[Playlist]

  def removeAlbumArt(playlistId: String, maybeUserId: Option[String]): F[Playlist]

  def deletePlaylist(playlistId: String, maybeUserId: Option[String]): F[Playlist]
}
