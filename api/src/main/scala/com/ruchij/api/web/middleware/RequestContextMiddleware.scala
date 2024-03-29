package com.ruchij.api.web.middleware

import cats.data.Kleisli
import cats.implicits._
import cats.{Applicative, Monad}
import com.ruchij.api.services.models.Context.RequestContext
import com.ruchij.api.web.headers.`X-Request-ID`
import com.ruchij.core.types.RandomGenerator
import org.http4s.{ContextRequest, HttpApp, Request, Response}

import java.util.UUID

object RequestContextMiddleware {

  def apply[F[_]: RandomGenerator[*[_], UUID]: Monad](
    contextHttpApp: Kleisli[F, ContextRequest[F, RequestContext], Response[F]]
  ): HttpApp[F] =
    Kleisli[F, Request[F], Response[F]] { request =>
      request.headers
        .get[`X-Request-ID`]
        .fold[F[String]](RandomGenerator[F, UUID].generate.map(_.toString)) { requestIdHeader =>
          Applicative[F].pure(requestIdHeader.value)
        }
        .flatMap { requestId =>
          contextHttpApp
            .run(ContextRequest(RequestContext(requestId), request))
            .map { response =>
              response.withHeaders {
                response.headers.put(`X-Request-ID`(requestId))
              }
            }
        }
    }

}
