package com.ruchij.api.web.routes

import java.time.Instant

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
import com.ruchij.core.types.FunctionKTypes
import org.http4s.{HttpDate, HttpRoutes, ResponseCookie, SameSite}
import org.http4s.dsl.Http4sDsl

object AuthenticationRoutes {
  def apply[F[_]: Sync](authenticationService: AuthenticationService[F])(implicit dsl: Http4sDsl[F]): HttpRoutes[F] = {
    import dsl._

    HttpRoutes.of {
      case request @ POST -> Root / "login" =>
        for {
          loginRequest <- request.as[LoginRequest]

          authenticationToken <- authenticationService.login(loginRequest.password)

          httpDate <- FunctionKTypes.eitherToF[Throwable, F].apply {
            HttpDate.fromInstant(Instant.ofEpochMilli(authenticationToken.expiresAt.getMillis))
          }

          response <-
            Created(authenticationToken).map {
              _.addCookie {
                ResponseCookie(
                  Authenticator.CookieName,
                  authenticationToken.secret.value,
                  Some(httpDate),
                  path = Some("/"),
                  sameSite = SameSite.None
                )
              }
            }
        }
        yield response
    }
  }
}
