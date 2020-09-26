package com.ruchij.api.web.middleware

import cats.data.{Kleisli, OptionT}
import com.ruchij.api.services.authentication.AuthenticationService
import org.http4s.{HttpRoutes, Request, Response}

object Authenticator {
  def secure[F[_]](authenticationService: AuthenticationService[F])(httpRoutes: HttpRoutes[F]): HttpRoutes[F] =
    Kleisli[OptionT[F, *], Request[F], Response[F]] {
      request => ???
    }
}
