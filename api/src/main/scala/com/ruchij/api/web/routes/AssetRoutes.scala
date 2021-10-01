package com.ruchij.api.web.routes

import cats.Applicative
import cats.data.NonEmptyList
import cats.effect.Sync
import cats.implicits._
import com.ruchij.api.services.models.Context.AuthenticatedRequestContext
import com.ruchij.api.web.responses.ResponseOps.AssetResponseOps
import com.ruchij.core.services.asset.AssetService
import com.ruchij.core.services.asset.AssetService.FileByteRange
import org.http4s.ContextRoutes
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.Range

object AssetRoutes {
  def apply[F[_]: Sync](assetService: AssetService[F])(implicit dsl: Http4sDsl[F]): ContextRoutes[AuthenticatedRequestContext, F] = {
    import dsl._

    ContextRoutes.of[AuthenticatedRequestContext, F] {
      case GET -> Root / "thumbnail" / id as AuthenticatedRequestContext(user, requestId) =>
          assetService.thumbnail(id).flatMap(_.asResponse)

      case GET -> Root / "snapshot" / id as AuthenticatedRequestContext(user, requestId) =>
        assetService.snapshot(id, user.nonAdminUserId).flatMap(_.asResponse)

      case authRequest @ GET -> Root / "video" / id as AuthenticatedRequestContext(user, requestId) =>
        for {
          maybeRange <- Applicative[F].pure {
            authRequest.req.headers.get[Range].collect { case Range(_, NonEmptyList(subRange, _)) => subRange }
          }

          videoFileAsset <-
            assetService.videoFile(
              id,
              user.nonAdminUserId,
              maybeRange.map(subRange => FileByteRange(subRange.first, subRange.second))
            )

          response <- videoFileAsset.asChunkSizeLimitedResponse
        }
        yield response
    }
  }
}
