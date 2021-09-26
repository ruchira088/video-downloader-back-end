package com.ruchij.api.web.middleware

import cats.data.{Kleisli, OptionT}
import cats.implicits._
import cats.{Applicative, ApplicativeError, Monad, MonadError}
import com.ruchij.api.daos.user.models.User
import com.ruchij.api.exceptions.AuthenticationException
import com.ruchij.api.services.authentication.AuthenticationService
import com.ruchij.api.services.authentication.AuthenticationService.Secret
import com.ruchij.api.services.authentication.models.AuthenticationToken
import com.ruchij.api.services.models.Context.{AuthenticatedRequestContext, RequestContext}
import com.ruchij.core.types.FunctionKTypes._
import org.http4s._
import org.http4s.headers.Authorization
import org.http4s.server.Middleware

import java.time.Instant

object Authenticator {
  type AuthenticatedRequestContextMiddleware[F[_]] =
    Middleware[OptionT[F, *], ContextRequest[F, AuthenticatedRequestContext], Response[F], ContextRequest[F, RequestContext], Response[F]]


  val CookieName = "authentication"

  private def bearerToken[F[_]](request: Request[F]): Option[Secret] =
    request.headers
      .get[Authorization]
      .map(_.credentials)
      .collect {
        case Credentials.Token(AuthScheme.Bearer, bearerToken) => Secret(bearerToken)
      }

  private def authenticationCookie[F[_]](request: Request[F]): Option[RequestCookie] =
    request.cookies.find(_.name == CookieName).filter(_.content.trim.nonEmpty)

  private def authenticatedUser[F[_]: Monad](
    authenticationService: AuthenticationService[F]
  ): Kleisli[OptionT[F, *], Request[F], (AuthenticationToken, User)] =
    Kleisli { request =>
      OptionT
        .fromOption[F](authenticationSecret(request))
        .semiflatMap(authenticationService.authenticate)
    }

  def authenticationSecret[F[_]](request: Request[F]): Option[Secret] =
    authenticationCookie(request)
      .map(cookie => Secret(cookie.content))
      .orElse(bearerToken(request))

  def middleware[F[+ _]: MonadError[*[_], Throwable]](
    authenticationService: AuthenticationService[F],
    strict: Boolean
  ): AuthenticatedRequestContextMiddleware[F] =
    httpAuthRoutes =>
      authenticatedUser(authenticationService).local[ContextRequest[F, RequestContext]](_.req)
        .flatMap {
          case (authenticationToken, user) =>
            Kleisli
              .ask[OptionT[F, *], ContextRequest[F, RequestContext]]
              .flatMapF {
                contextRequest =>
                  httpAuthRoutes.run {
                    ContextRequest(AuthenticatedRequestContext(user, contextRequest.context.requestId), contextRequest.req)
                  }
              }
              .flatMapF(response => OptionT.liftF(addCookie[F](authenticationToken, response)))
        }
        .mapF[OptionT[F, *], Response[F]] { optionT =>
          optionT.orElseF(onFailure[F](strict))
      }
  
  private def onFailure[F[+ _]: ApplicativeError[*[_], Throwable]](strict: Boolean): F[None.type] =
    if (strict)
      ApplicativeError[F, Throwable].raiseError(AuthenticationException.MissingAuthenticationToken)
    else Applicative[F].pure(None)

  def addCookie[F[_]: ApplicativeError[*[_], Throwable]](
    authenticationToken: AuthenticationToken,
    response: Response[F]
  ): F[Response[F]] =
    HttpDate
      .fromInstant(Instant.ofEpochMilli(authenticationToken.expiresAt.getMillis))
      .toType[F, Throwable]
      .map { httpDate =>
        response.addCookie {
          ResponseCookie(
            Authenticator.CookieName,
            authenticationToken.secret.value,
            Some(httpDate),
            path = Some("/"),
            secure = true,
            sameSite = Some(SameSite.None)
          )
        }
      }

}
