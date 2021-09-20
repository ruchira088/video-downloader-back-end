package com.ruchij.api.web.routes

import cats.Applicative
import cats.data.NonEmptyList
import cats.effect.Sync
import cats.implicits._
import com.ruchij.api.daos.user.models.User
import com.ruchij.api.web.responses.ResponseOps.AssetResponseOps
import com.ruchij.core.services.asset.AssetService
import org.http4s.AuthedRoutes
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.Range

object AssetRoutes {
  def apply[F[_]: Sync](assetService: AssetService[F])(implicit dsl: Http4sDsl[F]): AuthedRoutes[User, F] = {
    import dsl._

    AuthedRoutes.of[User, F] {
      case authRequest @ GET -> Root / "id" / id as user =>
        for {
          maybeRange <- Applicative[F].pure {
            authRequest.req.headers.get[Range].collect { case Range(_, NonEmptyList(subRange, _)) => subRange }
          }

          asset <- assetService.retrieve(id, maybeRange.map(_.first), maybeRange.flatMap(_.second))

          response <- asset.asResponse
        }
        yield response
    }
  }
}
