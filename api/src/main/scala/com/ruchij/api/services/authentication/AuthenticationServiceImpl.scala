package com.ruchij.api.services.authentication

import cats.data.OptionT
import cats.effect.Clock
import cats.implicits._
import cats.{Applicative, ApplicativeError, MonadError}
import com.ruchij.api.config.AuthenticationConfiguration
import com.ruchij.api.exceptions.AuthenticationException
import com.ruchij.api.services.authentication.AuthenticationService.{Password, Secret}
import com.ruchij.api.services.authentication.models.AuthenticationToken
import com.ruchij.api.services.authentication.models.AuthenticationToken.AuthenticationTokenKey
import com.ruchij.core.kv.KeySpacedKeyValueStore
import com.ruchij.core.types.JodaClock

class AuthenticationServiceImpl[F[_]: MonadError[*[_], Throwable]: Clock](
  keySpacedKeyValueStore: KeySpacedKeyValueStore[F, AuthenticationTokenKey, AuthenticationToken],
  authenticationConfiguration: AuthenticationConfiguration
) extends AuthenticationService[F] {
  override def login(password: Password): F[AuthenticationToken] =
    if ()

  override def authenticate(secret: Secret): F[AuthenticationToken] =
    OptionT(keySpacedKeyValueStore.get(AuthenticationTokenKey(secret)))
      .semiflatMap {
        case AuthenticationToken(secret, expiresAt, issuedAt, renewals) =>
          for {
            timestamp <- JodaClock[F].timestamp
            _ <- if (timestamp.isBefore(expiresAt)) Applicative[F].unit
            else
              ApplicativeError[F, Throwable].raiseError {
                AuthenticationException(s"Authentication token expired at $expiresAt")
              }

            authenticationToken = AuthenticationToken(secret, timestamp.plus(authenticationConfiguration.sessionDuration.toMillis), issuedAt, renewals + 1)
            _ <- keySpacedKeyValueStore.put(AuthenticationTokenKey(secret), authenticationToken)
          } yield authenticationToken
      }
      .getOrElseF[AuthenticationToken] {
        ApplicativeError[F, Throwable].raiseError(AuthenticationException("Authentication token not found"))
      }

  override def logout(secret: Secret): F[AuthenticationToken] =
    OptionT(keySpacedKeyValueStore.get(AuthenticationTokenKey(secret)))
      .semiflatTap {
        _ => keySpacedKeyValueStore.remove(AuthenticationTokenKey(secret))
      }
      .getOrElseF[AuthenticationToken] {
        ApplicativeError[F, Throwable].raiseError(AuthenticationException("Authentication token not found"))
      }
}
