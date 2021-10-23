package com.ruchij.api.web.routes

import cats.effect.Async
import cats.implicits._
import com.ruchij.api.services.authentication.AuthenticationService
import com.ruchij.api.services.models.Context.{AuthenticatedRequestContext, RequestContext}
import com.ruchij.api.web.requests.LoginRequest
import com.ruchij.api.web.requests.RequestOps.ContextRequestOpsSyntax
import org.http4s.circe.CirceEntityDecoder.circeEntityDecoder
import org.http4s.circe.CirceEntityEncoder.circeEntityEncoder
import io.circe.generic.auto._
import com.ruchij.core.circe.Decoders._
import com.ruchij.core.circe.Encoders._
import com.ruchij.api.web.middleware.Authenticator
import org.http4s.{AuthedRoutes, ContextRoutes, Response}
import org.http4s.dsl.Http4sDsl

object AuthenticationRoutes {
  def apply[F[+ _]: Async](authenticationService: AuthenticationService[F])(implicit dsl: Http4sDsl[F]): ContextRoutes[RequestContext, F] = {
    import dsl._

    val unauthenticatedRoutes =
      ContextRoutes.of[RequestContext, F] {
        case contextRequest @ POST -> Root / "login" as _ =>
          for {
            LoginRequest(email, password) <- contextRequest.to[LoginRequest]

            authenticationToken <- authenticationService.login(email, password)

            response <- Created(authenticationToken).flatMap(Authenticator.addCookie[F](authenticationToken, _))
          }
          yield response
      }

    val authenticatedRoutes =
      AuthedRoutes.of[AuthenticatedRequestContext, F] {
        case GET -> Root / "user" as AuthenticatedRequestContext(user, _) => Ok(user)

        case authRequest @ DELETE -> Root / "logout" as AuthenticatedRequestContext(user, _) =>
          Authenticator.authenticationSecret(authRequest.req)
            .fold[F[Response[F]]](NoContent()) { secret =>
              authenticationService.logout(secret)
                .productR(Ok(user))
            }
            .map(_.removeCookie(Authenticator.CookieName))
      }

    unauthenticatedRoutes <+> Authenticator.middleware(authenticationService, strict = true).apply(authenticatedRoutes)
  }

}
