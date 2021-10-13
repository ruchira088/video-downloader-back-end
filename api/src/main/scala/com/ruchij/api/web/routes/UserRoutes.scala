package com.ruchij.api.web.routes

import cats.effect.Sync
import cats.implicits._
import com.ruchij.api.services.authentication.AuthenticationService
import com.ruchij.api.services.models.Context.{AuthenticatedRequestContext, RequestContext}
import com.ruchij.api.services.user.UserService
import com.ruchij.api.web.middleware.Authenticator
import com.ruchij.api.web.requests.{CreateUserRequest, ResetPasswordRequest}
import com.ruchij.api.web.requests.RequestOps.ContextRequestOpsSyntax
import com.ruchij.api.web.responses.ResultResponse
import com.ruchij.core.circe.Decoders._
import com.ruchij.core.circe.Encoders._
import io.circe.generic.auto.{exportDecoder, exportEncoder}
import org.http4s.ContextRoutes
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.dsl.Http4sDsl

object UserRoutes {
  def apply[F[+ _]: Sync](userService: UserService[F], authenticationService: AuthenticationService[F])(
    implicit dsl: Http4sDsl[F]
  ): ContextRoutes[RequestContext, F] = {
    import dsl._

    val unauthenticatedRoutes =
      ContextRoutes.of[RequestContext, F] {
        case contextRequest @ POST -> Root as _ =>
          for {
            CreateUserRequest(firstName, lastName, email, password) <- contextRequest.to[CreateUserRequest]
            user <- userService.create(firstName, lastName, email, password)
            response <- Created(user)
          } yield response

        case contextRequest @ PUT -> Root / "password-reset" as _ =>
          for {
            ResetPasswordRequest(email) <- contextRequest.to[ResetPasswordRequest]
            _ <- userService.forgotPassword(email)
            response <- Ok(ResultResponse(s"Password reset token sent to ${email.value}"))
          }
          yield response
      }

    val authenticatedRoutes =
      ContextRoutes.of[AuthenticatedRequestContext, F] {
        case DELETE -> Root / "id" / userId as AuthenticatedRequestContext(adminUser, _) =>
          userService.delete(userId, adminUser)
            .flatMap(user => Ok(user))
      }

    unauthenticatedRoutes <+>
      Authenticator.middleware[F](authenticationService, strict = true).apply(authenticatedRoutes)
  }
}
