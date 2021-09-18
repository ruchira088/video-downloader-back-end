package com.ruchij.api.services.playlist

import com.ruchij.api.daos.models.{Playlist, PlaylistSortBy}
import com.ruchij.core.services.models.Order
import fs2.Stream
import org.http4s.MediaType

trait PlaylistService[F[_]] {
  def create(title: String, description: Option[String]): F[Playlist]

  def updatePlaylist(
    playlistId: String,
    maybeTitle: Option[String],
    maybeDescription: Option[String],
    maybeVideoIdList: Option[Seq[String]]
  ): F[Playlist]

  def fetchById(playlistId: String): F[Playlist]

  def search(
    maybeSearchTerm: Option[String],
    pageSize: Int,
    pageNumber: Int,
    order: Order,
    playlistSortBy: PlaylistSortBy
  ): F[Seq[Playlist]]

  def addAlbumArt(playlistId: String, fileName: String, mediaType: MediaType, data: Stream[F, Byte]): F[Playlist]

  def removeAlbumArt(playlistId: String): F[Playlist]

  def deletePlaylist(playlistId: String): F[Playlist]
}
