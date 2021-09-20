package com.ruchij.api.web.routes

import cats.effect.Sync
import cats.implicits._
import com.ruchij.api.daos.user.models.User
import com.ruchij.api.services.authentication.AuthenticationService
import com.ruchij.api.web.requests.LoginRequest
import com.ruchij.api.web.requests.RequestOps.RequestOpsSyntax
import org.http4s.circe.CirceEntityDecoder.circeEntityDecoder
import org.http4s.circe.CirceEntityEncoder.circeEntityEncoder
import io.circe.generic.auto._
import com.ruchij.core.circe.Decoders.stringWrapperDecoder
import com.ruchij.core.circe.Encoders.{dateTimeEncoder, stringWrapperEncoder}
import com.ruchij.api.web.middleware.Authenticator
import org.http4s.{AuthedRoutes, HttpRoutes, Response}
import org.http4s.dsl.Http4sDsl

object AuthenticationRoutes {
  def apply[F[+ _]: Sync](authenticationService: AuthenticationService[F])(implicit dsl: Http4sDsl[F]): HttpRoutes[F] = {
    import dsl._

    val unauthenticatedRoutes =
      HttpRoutes.of[F] {
        case request@POST -> Root / "login" =>
          for {
            LoginRequest(email, password) <- request.to[LoginRequest]

            authenticationToken <- authenticationService.login(email, password)

            response <- Created(authenticationToken).flatMap(Authenticator.addCookie[F](authenticationToken, _))
          }
          yield response
      }

    val authenticatedRoutes =
      AuthedRoutes.of[User, F] {
        case GET -> Root / "user" as user => Ok(user)

        case authRequest @ DELETE -> Root / "logout" as user =>
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
