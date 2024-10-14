package com.ruchij.api.web.routes

import cats.Applicative
import cats.data.NonEmptyList
import cats.effect.Sync
import cats.implicits._
import com.ruchij.api.services.asset.AssetService
import com.ruchij.api.services.models.Context.AuthenticatedRequestContext
import com.ruchij.api.web.responses.ResponseOps.AssetResponseOps
import AssetService.FileByteRange
import org.http4s.ContextRoutes
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.Range

object AssetRoutes {
  private val MaxVideoServeSize = 5 * 1000 * 1000 // 5MB

  def apply[F[_]: Sync](assetService: AssetService[F])(implicit dsl: Http4sDsl[F]): ContextRoutes[AuthenticatedRequestContext, F] = {
    import dsl._

    ContextRoutes.of[AuthenticatedRequestContext, F] {
      case GET -> Root / "thumbnail" / "id" / id as _ =>
          assetService.thumbnail(id).flatMap(_.asResponse)

      case GET -> Root / "snapshot" / "id" / id as AuthenticatedRequestContext(user, _) =>
        assetService.snapshot(id, user).flatMap(_.asResponse)

      case authRequest @ GET -> Root / "video" / "id" / id as AuthenticatedRequestContext(user, _) =>
        for {
          maybeRange <- Applicative[F].pure {
            authRequest.req.headers.get[Range].collect { case Range(_, NonEmptyList(subRange, _)) => subRange }
          }

          videoFileAsset <-
            assetService.videoFile(
              id,
              user,
              maybeRange.map(subRange => FileByteRange(subRange.first, subRange.second)),
              Some(MaxVideoServeSize)
            )

          response <- videoFileAsset.asResponse
        }
        yield response
    }
  }
}
