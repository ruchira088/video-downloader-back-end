package com.ruchij.api.web.routes

import cats.effect.Sync
import cats.implicits._
import com.ruchij.core.circe.Encoders._
import com.ruchij.api.daos.models.PlaylistSortBy
import com.ruchij.api.services.playlist.PlaylistService
import com.ruchij.api.web.requests.CreatePlaylistRequest
import com.ruchij.api.web.requests.RequestOps.RequestOpsSyntax
import com.ruchij.api.web.requests.queryparams.PagingQuery
import com.ruchij.api.web.requests.queryparams.QueryParameter.enumQueryParamDecoder
import com.ruchij.api.web.requests.queryparams.SingleValueQueryParameter.SearchTermQueryParameter
import com.ruchij.api.web.responses.PagingResponse
import io.circe.generic.auto._
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec._
import org.http4s.circe.encodeUri
import org.http4s.dsl.Http4sDsl

object PlaylistRoutes {

  def apply[F[_]: Sync](playlistService: PlaylistService[F])(implicit dsl: Http4sDsl[F]): HttpRoutes[F] = {
    import dsl._

    HttpRoutes.of {
      case request @ POST -> Root =>
        for {
          CreatePlaylistRequest(title, description) <- request.to[CreatePlaylistRequest]
          playlist <- playlistService.create(title, description)
          response <- Created(playlist)
        } yield response

      case GET -> Root :? queryParameters =>
        for {
          pageQuery <- PagingQuery.from[F, PlaylistSortBy].run(queryParameters)
          maybeSearchTerm <- SearchTermQueryParameter.parse[F].run(queryParameters)

          playlists <-
            playlistService.list(
              maybeSearchTerm,
              pageQuery.pageSize,
              pageQuery.pageNumber,
              pageQuery.order,
              pageQuery.maybeSortBy.getOrElse(PlaylistSortBy.CreatedAt)
            )

          response <- Ok(PagingResponse(playlists, pageQuery.pageSize, pageQuery.pageNumber, pageQuery.order, pageQuery.maybeSortBy))
        } yield response

      case GET -> Root / playlistId =>
        for {
          playlist <- playlistService.fetchById(playlistId)
          response <- Ok(playlist)
        } yield response

      case PUT -> Root / playlistId => ???

      case PUT -> Root / playlistId / "album-art" => ???

      case DELETE -> Root / playlistId / "album-art" => ???

      case PUT -> Root / playlistId / "video" / videoId => ???

      case DELETE -> Root / playlistId / "video" / videoId => ???

      case DELETE -> Root / playlistId => ???
    }
  }

}
