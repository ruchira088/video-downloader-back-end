package com.ruchij.api.web.routes

import cats.effect.Sync
import cats.implicits._
import com.ruchij.core.circe.Encoders._
import com.ruchij.api.daos.playlist.models.PlaylistSortBy
import com.ruchij.api.daos.user.models.User
import com.ruchij.api.services.playlist.PlaylistService
import com.ruchij.api.web.requests.{CreatePlaylistRequest, FileAsset, UpdatePlaylistRequest}
import com.ruchij.api.web.requests.RequestOps.AuthRequestOpsSyntax
import com.ruchij.api.web.requests.queryparams.PagingQuery
import com.ruchij.api.web.requests.queryparams.QueryParameter.enumQueryParamDecoder
import com.ruchij.api.web.requests.queryparams.SingleValueQueryParameter.SearchTermQueryParameter
import com.ruchij.api.web.responses.PagingResponse
import io.circe.generic.auto._
import org.http4s.AuthedRoutes
import org.http4s.circe.CirceEntityCodec._
import org.http4s.circe.encodeUri
import org.http4s.dsl.Http4sDsl

object PlaylistRoutes {

  def apply[F[_]: Sync](playlistService: PlaylistService[F])(implicit dsl: Http4sDsl[F]): AuthedRoutes[User, F] = {
    import dsl._

    AuthedRoutes.of[User, F] {
      case authRequest @ POST -> Root as user =>
        for {
          CreatePlaylistRequest(title, description) <- authRequest.to[CreatePlaylistRequest]
          playlist <- playlistService.create(title, description)
          response <- Created(playlist)
        } yield response

      case GET -> Root :? queryParameters as user =>
        for {
          pageQuery <- PagingQuery.from[F, PlaylistSortBy].run(queryParameters)
          maybeSearchTerm <- SearchTermQueryParameter.parse[F].run(queryParameters)

          playlists <-
            playlistService.search(
              maybeSearchTerm,
              pageQuery.pageSize,
              pageQuery.pageNumber,
              pageQuery.order,
              pageQuery.maybeSortBy.getOrElse(PlaylistSortBy.CreatedAt)
            )

          response <- Ok(PagingResponse(playlists, pageQuery.pageSize, pageQuery.pageNumber, pageQuery.order, pageQuery.maybeSortBy))
        } yield response

      case GET -> Root / "id" / playlistId as user =>
        for {
          playlist <- playlistService.fetchById(playlistId)
          response <- Ok(playlist)
        } yield response

      case authRequest @ PUT -> Root / "id" / playlistId as user =>
        for {
          UpdatePlaylistRequest(maybeTitle, maybeDescription, maybeVideoIdList) <- authRequest.to[UpdatePlaylistRequest]
          playlist <- playlistService.updatePlaylist(playlistId, maybeTitle, maybeDescription, maybeVideoIdList)
          response <- Ok(playlist)
        }
        yield response

      case authRequest @ PUT -> Root / "id" / playlistId / "album-art" as user =>
        authRequest.to[FileAsset[F]]
          .flatMap { fileAsset => playlistService.addAlbumArt(playlistId, fileAsset.fileName, fileAsset.mediaType, fileAsset.data) }
          .flatMap(playlist => Ok(playlist))

      case DELETE -> Root / "id" / playlistId / "album-art" as user =>
        playlistService.removeAlbumArt(playlistId).flatMap(playlist => Ok(playlist))

      case DELETE -> Root / "id" / playlistId as user =>
        playlistService.deletePlaylist(playlistId).flatMap(playlist => Ok(playlist))
    }
  }

}
