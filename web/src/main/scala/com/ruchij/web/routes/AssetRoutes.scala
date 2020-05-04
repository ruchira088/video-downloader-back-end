package com.ruchij.web.routes

import cats.Applicative
import cats.data.NonEmptyList
import cats.effect.Sync
import cats.implicits._
import com.ruchij.services.asset.AssetService
import com.ruchij.web.responses.ResponseOps.AssetResponseOps
import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.Range

object AssetRoutes {
  def apply[F[_]: Sync](assetService: AssetService[F])(implicit dsl: Http4sDsl[F]): HttpRoutes[F] = {
    import dsl._

    HttpRoutes.of {
      case request @ GET -> Root / "id" / id =>
        for {
          range <- Applicative[F].pure {
            request.headers.get(Range).map { case Range(_, NonEmptyList(subRange, _)) => subRange }
          }

          asset <- assetService.retrieve(id, range.map(_.first), range.flatMap(_.second))

          response <- asset.asResponse
        }
        yield response
    }
  }
}
