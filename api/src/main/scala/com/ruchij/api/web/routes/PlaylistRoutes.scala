package com.ruchij.api.web.routes

import cats.effect.Async
import cats.implicits._

import com.ruchij.api.daos.playlist.models.PlaylistSortBy
import com.ruchij.api.services.models.Context.AuthenticatedRequestContext
import com.ruchij.api.services.playlist.PlaylistService
import com.ruchij.api.web.requests.{CreatePlaylistRequest, FileAsset, UpdatePlaylistRequest}
import com.ruchij.api.web.requests.RequestOps.ContextRequestOpsSyntax
import com.ruchij.api.web.requests.queryparams.PagingAndSortingQuery
import com.ruchij.api.web.requests.queryparams.QueryParameter.enumQueryParamDecoder
import com.ruchij.api.web.requests.queryparams.SingleValueQueryParameter.SearchTermQueryParameter
import com.ruchij.api.web.responses.PagingResponse
import com.ruchij.core.circe.Encoders._
import io.circe.generic.auto._
import org.http4s.ContextRoutes
import org.http4s.circe.CirceEntityCodec._
import org.http4s.circe.encodeUri
import org.http4s.dsl.Http4sDsl

object PlaylistRoutes {

  def apply[F[_]: Async](playlistService: PlaylistService[F])(implicit dsl: Http4sDsl[F]): ContextRoutes[AuthenticatedRequestContext, F] = {
    import dsl._

    ContextRoutes.of[AuthenticatedRequestContext, F] {
      case contextRequest @ POST -> Root as AuthenticatedRequestContext(user, _) =>
        for {
          CreatePlaylistRequest(title, description) <- contextRequest.to[CreatePlaylistRequest]
          playlist <- playlistService.create(title, description, user.id)
          response <- Created(playlist)
        } yield response

      case GET -> Root :? queryParameters as AuthenticatedRequestContext(user, _) =>
        for {
          pageQuery <- PagingAndSortingQuery.from[F, PlaylistSortBy].run(queryParameters)
          maybeSearchTerm <- SearchTermQueryParameter.parse[F].run(queryParameters)

          playlists <-
            playlistService.search(
              maybeSearchTerm,
              pageQuery.pageSize,
              pageQuery.pageNumber,
              pageQuery.order,
              pageQuery.maybeSortBy.getOrElse(PlaylistSortBy.CreatedAt),
              user.nonAdminUserId
            )

          response <- Ok(PagingResponse(playlists, pageQuery.pageSize, pageQuery.pageNumber, pageQuery.order, pageQuery.maybeSortBy))
        } yield response

      case GET -> Root / "id" / playlistId as AuthenticatedRequestContext(user, _) =>
        for {
          playlist <- playlistService.fetchById(playlistId, user.nonAdminUserId)
          response <- Ok(playlist)
        } yield response

      case contextRequest @ PUT -> Root / "id" / playlistId as AuthenticatedRequestContext(user, _) =>
        for {
          UpdatePlaylistRequest(maybeTitle, maybeDescription, maybeVideoIdList) <- contextRequest.to[UpdatePlaylistRequest]
          playlist <- playlistService.updatePlaylist(playlistId, maybeTitle, maybeDescription, maybeVideoIdList, user.nonAdminUserId)
          response <- Ok(playlist)
        }
        yield response

      case authRequest @ PUT -> Root / "id" / playlistId / "album-art" as AuthenticatedRequestContext(user, _) =>
        authRequest.to[FileAsset[F]]
          .flatMap { fileAsset => playlistService.addAlbumArt(playlistId, fileAsset.fileName, fileAsset.mediaType, fileAsset.data, user.nonAdminUserId) }
          .flatMap(playlist => Ok(playlist))

      case DELETE -> Root / "id" / playlistId / "album-art" as AuthenticatedRequestContext(user, _) =>
        playlistService.removeAlbumArt(playlistId, user.nonAdminUserId).flatMap(playlist => Ok(playlist))

      case DELETE -> Root / "id" / playlistId as AuthenticatedRequestContext(user, _) =>
        playlistService.deletePlaylist(playlistId, user.nonAdminUserId).flatMap(playlist => Ok(playlist))
    }
  }

}
