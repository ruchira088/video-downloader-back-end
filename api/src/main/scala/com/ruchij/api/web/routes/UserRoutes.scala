package com.ruchij.api.web.routes

import cats.effect.Sync
import cats.implicits._
import com.ruchij.api.services.models.Context.RequestContext
import com.ruchij.api.services.user.UserService
import com.ruchij.api.web.requests.CreateUserRequest
import com.ruchij.api.web.requests.RequestOps.ContextRequestOpsSyntax
import com.ruchij.core.circe.Decoders._
import com.ruchij.core.circe.Encoders._
import io.circe.generic.auto.{exportDecoder, exportEncoder}
import org.http4s.ContextRoutes
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.dsl.Http4sDsl

object UserRoutes {
  def apply[F[_]: Sync](userService: UserService[F])(implicit dsl: Http4sDsl[F]): ContextRoutes[RequestContext, F] = {
    import dsl._

    ContextRoutes.of[RequestContext, F] {
      case contextRequest @ POST -> Root as requestContext =>
        for {
          CreateUserRequest(firstName, lastName, email, password) <- contextRequest.to[CreateUserRequest]
          user <- userService.create(firstName, lastName, email, password)
          response <- Created(user)
        }
        yield response
    }
  }
}
