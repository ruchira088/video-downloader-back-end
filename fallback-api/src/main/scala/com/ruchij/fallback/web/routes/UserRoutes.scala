package com.ruchij.fallback.web.routes

import cats.effect.Async
import cats.implicits._
import com.ruchij.api.services.models.Context.RequestContext
import com.ruchij.api.web.requests.RequestOps.ContextRequestOpsSyntax
import com.ruchij.core.circe.Decoders.stringWrapperDecoder
import com.ruchij.core.circe.Encoders._
import com.ruchij.fallback.services.user.UserService
import com.ruchij.fallback.web.requests.CreateUserRequest
import io.circe.generic.auto._
import org.http4s.ContextRoutes
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.Http4sDsl

object UserRoutes {

  def apply[F[_]: Async](
    fallbackUserService: UserService[F]
  )(implicit http4sDsl: Http4sDsl[F]): ContextRoutes[RequestContext, F] = {
    import http4sDsl._

    ContextRoutes.of {
      case request @ POST -> Root as _ =>
        for {
          fallbackUserRequest <- request.to[CreateUserRequest]
          fallbackUser <- fallbackUserService.create(fallbackUserRequest.email, fallbackUserRequest.password)
          response <- Created(fallbackUser)
        } yield response
    }
  }

}
