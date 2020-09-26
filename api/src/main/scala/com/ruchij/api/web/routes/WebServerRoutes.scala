package com.ruchij.api.web.routes

import cats.data.OptionT
import cats.effect.{Blocker, ContextShift, Sync}
import org.http4s.dsl.Http4sDsl
import org.http4s.{HttpRoutes, Response, StaticFile}

object WebServerRoutes {
  def apply[F[_]: Sync: ContextShift](blocker: Blocker)(implicit dsl: Http4sDsl[F]): HttpRoutes[F] = {
    import dsl._

    HttpRoutes {
      case request @ GET -> Root / (fileName @ "favicon.ico") =>
        StaticFile.fromResource(fileName, blocker, Some(request))

      case _ => OptionT.none[F, Response[F]]
    }
  }
}
