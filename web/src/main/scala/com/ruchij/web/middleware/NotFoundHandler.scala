package com.ruchij.web.middleware

import cats.effect.Sync
import com.ruchij.exceptions.ResourceNotFoundException
import org.http4s.{HttpApp, HttpRoutes}

object NotFoundHandler {

  def apply[F[_]: Sync](httpRoutes: HttpRoutes[F]): HttpApp[F] =
    HttpApp[F] {
      request => httpRoutes.run(request).getOrElseF {
        Sync[F].raiseError {
          ResourceNotFoundException(s"Endpoint not found: ${request.method} ${request.uri}")
        }
      }
    }
}
