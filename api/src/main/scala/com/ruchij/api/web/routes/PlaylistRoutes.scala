package com.ruchij.api.web.routes

import cats.effect.Sync
import cats.implicits._
import com.ruchij.core.circe.Encoders._
import com.ruchij.api.daos.playlist.models.PlaylistSortBy
import com.ruchij.api.services.models.Context.AuthenticatedRequestContext
import com.ruchij.api.services.playlist.PlaylistService
import com.ruchij.api.web.requests.{CreatePlaylistRequest, FileAsset, UpdatePlaylistRequest}
import com.ruchij.api.web.requests.RequestOps.ContextRequestOpsSyntax
import com.ruchij.api.web.requests.queryparams.PagingQuery
import com.ruchij.api.web.requests.queryparams.QueryParameter.enumQueryParamDecoder
import com.ruchij.api.web.requests.queryparams.SingleValueQueryParameter.SearchTermQueryParameter
import com.ruchij.api.web.responses.PagingResponse
import io.circe.generic.auto._
import org.http4s.ContextRoutes
import org.http4s.circe.CirceEntityCodec._
import org.http4s.circe.encodeUri
import org.http4s.dsl.Http4sDsl

object PlaylistRoutes {

  def apply[F[_]: Sync](playlistService: PlaylistService[F])(implicit dsl: Http4sDsl[F]): ContextRoutes[AuthenticatedRequestContext, F] = {
    import dsl._

    ContextRoutes.of[AuthenticatedRequestContext, F] {
      case contextRequest @ POST -> Root as _ =>
        for {
          CreatePlaylistRequest(title, description) <- contextRequest.to[CreatePlaylistRequest]
          playlist <- playlistService.create(title, description)
          response <- Created(playlist)
        } yield response

      case GET -> Root :? queryParameters as _ =>
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

      case GET -> Root / "id" / playlistId as _ =>
        for {
          playlist <- playlistService.fetchById(playlistId)
          response <- Ok(playlist)
        } yield response

      case contextRequest @ PUT -> Root / "id" / playlistId as _ =>
        for {
          UpdatePlaylistRequest(maybeTitle, maybeDescription, maybeVideoIdList) <- contextRequest.to[UpdatePlaylistRequest]
          playlist <- playlistService.updatePlaylist(playlistId, maybeTitle, maybeDescription, maybeVideoIdList)
          response <- Ok(playlist)
        }
        yield response

      case authRequest @ PUT -> Root / "id" / playlistId / "album-art" as _ =>
        authRequest.to[FileAsset[F]]
          .flatMap { fileAsset => playlistService.addAlbumArt(playlistId, fileAsset.fileName, fileAsset.mediaType, fileAsset.data) }
          .flatMap(playlist => Ok(playlist))

      case DELETE -> Root / "id" / playlistId / "album-art" as _ =>
        playlistService.removeAlbumArt(playlistId).flatMap(playlist => Ok(playlist))

      case DELETE -> Root / "id" / playlistId as _ =>
        playlistService.deletePlaylist(playlistId).flatMap(playlist => Ok(playlist))
    }
  }

}
