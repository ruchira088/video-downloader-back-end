package com.ruchij.api.web.middleware

import cats.data.{Kleisli, OptionT}
import cats.implicits._
import cats.{ApplicativeError, MonadThrow}
import com.ruchij.api.exceptions.AuthenticationException
import com.ruchij.api.services.authentication.AuthenticationService
import com.ruchij.api.services.authentication.AuthenticationService.Secret
import com.ruchij.api.services.authentication.models.AuthenticationToken
import com.ruchij.api.services.models.Context.{AuthenticatedRequestContext, RequestContext}
import com.ruchij.api.web.headers.`X-User-ID`
import com.ruchij.core.types.FunctionKTypes._
import org.http4s._
import org.http4s.headers.Authorization
import org.http4s.server.Middleware

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

  def authenticationSecret[F[_]](request: Request[F]): Option[Secret] =
    authenticationCookie(request)
      .map(cookie => Secret(cookie.content))
      .orElse(bearerToken(request))

  /**
    * Authenticates the request before running the routes. Only a request without credentials is rejected (when
    * strict); an authenticated request that matches none of the routes yields None, so it falls through to the usual
    * not-found handling instead of being treated as unauthenticated (which would also sign the user out).
    */
  def middleware[F[_]: MonadThrow](
    authenticationService: AuthenticationService[F],
    strict: Boolean
  ): AuthenticatedRequestContextMiddleware[F] =
    httpAuthRoutes =>
      Kleisli { contextRequest =>
        // Deferred, so that nothing runs when the routes are combined with others (e.g. using <+>) that match first
        OptionT.liftF(MonadThrow[F].unit).flatMap { _ =>
          authenticationSecret(contextRequest.req).fold(onMissingCredentials[F](strict)) { secret =>
            OptionT
              .liftF(authenticationService.authenticate(secret))
              .flatMap {
                case (authenticationToken, user) =>
                  httpAuthRoutes
                    .run {
                      ContextRequest(
                        AuthenticatedRequestContext(user, contextRequest.context.requestId),
                        contextRequest.req
                      )
                    }
                    .semiflatMap(response => addCookie[F](authenticationToken, response))
                    .map(response => response.withHeaders(response.headers.put(`X-User-ID`(user.id))))
              }
          }
        }
      }

  private def onMissingCredentials[F[_]: ApplicativeError[*[_], Throwable]](strict: Boolean): OptionT[F, Response[F]] =
    if (strict)
      OptionT.liftF(ApplicativeError[F, Throwable].raiseError(AuthenticationException.MissingAuthenticationToken))
    else OptionT.none[F, Response[F]]

  def addCookie[F[_]: ApplicativeError[*[_], Throwable]](
    authenticationToken: AuthenticationToken,
    response: Response[F]
  ): F[Response[F]] =
    HttpDate
      .fromInstant(authenticationToken.expiresAt)
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
