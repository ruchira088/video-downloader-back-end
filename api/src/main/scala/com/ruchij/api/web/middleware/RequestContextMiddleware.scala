package com.ruchij.api.web.middleware

import cats.data.{Kleisli, OptionT}
import cats.implicits._
import cats.{Applicative, Monad}
import com.ruchij.api.services.models.Context.RequestContext
import com.ruchij.api.web.headers.`X-Request-ID`
import com.ruchij.core.types.RandomGenerator
import org.http4s.{ContextRequest, HttpRoutes, Request, Response}

import java.util.UUID

object RequestContextMiddleware {

  def apply[F[+ _]: RandomGenerator[*[_], UUID]: Monad](
    contextRoutes: Kleisli[OptionT[F, *], ContextRequest[F, RequestContext], Response[F]]
  ): HttpRoutes[F] =
    Kleisli[OptionT[F, *], Request[F], Response[F]] { request =>
      request.headers
        .get[`X-Request-ID`]
        .fold[OptionT[F, String]](OptionT.liftF(RandomGenerator[F, UUID].generate.map(_.toString))) { requestIdHeader =>
          Applicative[OptionT[F, *]].pure(requestIdHeader.value)
        }
        .flatMap { requestId =>
          contextRoutes.run(ContextRequest(RequestContext(requestId), request))
        }
    }

}
