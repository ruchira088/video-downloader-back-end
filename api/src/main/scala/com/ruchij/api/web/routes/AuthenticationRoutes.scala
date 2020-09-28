package com.ruchij.api.web.routes

import cats.effect.Sync
import cats.implicits._
import com.ruchij.api.services.authentication.AuthenticationService
import com.ruchij.api.web.requests.LoginRequest
import org.http4s.circe.CirceEntityDecoder.circeEntityDecoder
import org.http4s.circe.CirceEntityEncoder.circeEntityEncoder
import io.circe.generic.auto._
import com.ruchij.api.circe.Decoders.stringWrapperDecoder
import com.ruchij.api.circe.Encoders.{dateTimeEncoder, stringWrapperEncoder}
import com.ruchij.api.web.middleware.Authenticator
import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl

object AuthenticationRoutes {
  def apply[F[_]: Sync](authenticationService: AuthenticationService[F])(implicit dsl: Http4sDsl[F]): HttpRoutes[F] = {
    import dsl._

    HttpRoutes.of {
      case request @ POST -> Root / "login" =>
        for {
          loginRequest <- request.as[LoginRequest]

          authenticationToken <- authenticationService.login(loginRequest.password)

          response <- Created(authenticationToken).flatMap(Authenticator.addCookie[F](authenticationToken, _))
        }
        yield response

      case request @ DELETE -> Root / "logout" =>
        Authenticator.authenticationToken(authenticationService)
          .run(request)
          .semiflatMap(token => authenticationService.logout(token.secret))
          .semiflatMap(token => Ok(token))
          .getOrElseF(NoContent())
          .map(_.removeCookie(Authenticator.CookieName))

    }
  }
}
