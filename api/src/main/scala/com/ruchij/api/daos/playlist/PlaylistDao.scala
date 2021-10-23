package com.ruchij.api.daos.playlist

import com.ruchij.api.daos.playlist.models.{Playlist, PlaylistSortBy}
import com.ruchij.core.services.models.Order

trait PlaylistDao[F[_]] {
  def insert(playlist: Playlist): F[Int]

  def update(
    playlistId: String,
    maybeTitle: Option[String],
    maybeDescription: Option[String],
    maybeVideoIds: Option[Seq[String]],
    maybeAlbumArt: Option[Either[Unit, String]],
    maybeUserId: Option[String]
  ): F[Int]

  def findById(playlistId: String, maybeUserId: Option[String]): F[Option[Playlist]]

  def search(
    maybeSearchTerm: Option[String],
    pageSize: Int,
    pageNumber: Int,
    order: Order,
    sortBy: PlaylistSortBy,
    maybeUserId: Option[String]
  ): F[Seq[Playlist]]

  def deleteById(playlistId: String, maybeUserId: Option[String]): F[Int]
}
