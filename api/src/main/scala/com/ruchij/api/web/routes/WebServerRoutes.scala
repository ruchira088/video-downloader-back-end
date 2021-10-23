package com.ruchij.api.web.routes

import cats.data.OptionT
import cats.effect.Sync
import com.ruchij.api.services.models.Context.RequestContext
import org.http4s.dsl.Http4sDsl
import org.http4s.{ContextRoutes, Response, StaticFile}

object WebServerRoutes {
  def apply[F[_]: Sync](implicit dsl: Http4sDsl[F]): ContextRoutes[RequestContext, F] = {
    import dsl._

    ContextRoutes[RequestContext, F] {
      case contextRequest @ GET -> Root / (fileName @ "favicon.ico") as _ =>
        StaticFile.fromResource(fileName, Some(contextRequest.req))

      case _ => OptionT.none[F, Response[F]]
    }
  }
}
