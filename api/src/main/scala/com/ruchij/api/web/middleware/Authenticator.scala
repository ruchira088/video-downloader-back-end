package com.ruchij.api.web.middleware

import java.time.Instant

import cats.data.{Kleisli, OptionT}
import cats.implicits._
import cats.{Applicative, ApplicativeError, MonadError}
import com.ruchij.api.exceptions.AuthenticationException
import com.ruchij.api.services.authentication.AuthenticationService
import com.ruchij.api.services.authentication.AuthenticationService.Secret
import com.ruchij.api.services.authentication.models.AuthenticationToken
import com.ruchij.core.types.FunctionKTypes
import org.http4s.server.HttpMiddleware
import org.http4s._

object Authenticator {
  val CookieName = "authentication"

  def authenticationCookie[F[_]: Applicative](
    authenticationService: AuthenticationService[F]
  ): Kleisli[OptionT[F, *], Request[F], AuthenticationToken] =
    Kleisli[OptionT[F, *], Request[F], AuthenticationToken] { request =>
      request.cookies
        .find(_.name == CookieName)
        .fold[OptionT[F, AuthenticationToken]](OptionT.none[F, AuthenticationToken]) { cookie =>
          OptionT.liftF(authenticationService.authenticate(Secret(cookie.content)))
        }
    }

  def middleware[F[_]: MonadError[*[_], Throwable]](
    authenticationService: AuthenticationService[F],
    strict: Boolean
  ): HttpMiddleware[F] =
    httpRoutes =>
      authenticationCookie(authenticationService)
        .mapF(_.value)
        .flatMapF {
          _.fold[F[Option[AuthenticationToken]]](onFailure[F](strict).map(identity[Option[AuthenticationToken]])) {
            authenticationToken =>
              Applicative[F].pure(Some(authenticationToken))
          }
        }
        .mapF(OptionT.apply[F, AuthenticationToken])
        .flatMap { authenticationToken =>
          httpRoutes.flatMapF { result =>
            OptionT.liftF(addCookie[F](authenticationToken, result))
          }
      }

  def onFailure[F[_]: ApplicativeError[*[_], Throwable]](strict: Boolean): F[None.type] =
    if (strict)
      ApplicativeError[F, Throwable].raiseError(AuthenticationException("Authentication cookie not found"))
    else Applicative[F].pure(None)

  def addCookie[F[_]: MonadError[*[_], Throwable]](
    authenticationToken: AuthenticationToken,
    response: Response[F]
  ): F[Response[F]] =
    FunctionKTypes
      .eitherToF[Throwable, F]
      .apply {
        HttpDate.fromInstant(Instant.ofEpochMilli(authenticationToken.expiresAt.getMillis))
      }
      .map { httpDate =>
        response.addCookie {
          ResponseCookie(
            Authenticator.CookieName,
            authenticationToken.secret.value,
            Some(httpDate),
            path = Some("/"),
            secure = true,
            sameSite = SameSite.None
          )
        }
      }

}
