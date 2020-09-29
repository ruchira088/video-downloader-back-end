package com.ruchij.api.services.authentication

import cats.data.OptionT
import cats.effect.{Blocker, Clock, ContextShift, Sync}
import cats.implicits._
import cats.{Applicative, ApplicativeError}
import com.ruchij.api.config.AuthenticationConfiguration
import com.ruchij.api.exceptions.AuthenticationException
import com.ruchij.api.services.authentication.AuthenticationService.{Password, Secret}
import com.ruchij.api.services.authentication.models.AuthenticationToken
import com.ruchij.api.services.authentication.models.AuthenticationToken.AuthenticationTokenKey
import com.ruchij.core.kv.KeySpacedKeyValueStore
import com.ruchij.core.types.{JodaClock, RandomGenerator}
import org.mindrot.jbcrypt.BCrypt

class AuthenticationServiceImpl[F[+ _]: Sync: ContextShift: Clock](
  keySpacedKeyValueStore: KeySpacedKeyValueStore[F, AuthenticationTokenKey, AuthenticationToken],
  authenticationConfiguration: AuthenticationConfiguration,
  blocker: Blocker
) extends AuthenticationService[F] {
  override def login(password: Password): F[AuthenticationToken] =
    blocker.delay {
      BCrypt.checkpw(password.value, authenticationConfiguration.hashedPassword.value)
    }
      .flatMap { isAuthenticated =>
        if (isAuthenticated)
          for {
            timestamp <- JodaClock[F].timestamp
            secret <- RandomGenerator[F, Secret].generate

            authenticationToken = AuthenticationToken(secret, timestamp.plus(authenticationConfiguration.sessionDuration.toMillis), timestamp, 0)
            _ <- keySpacedKeyValueStore.put(AuthenticationTokenKey(secret), authenticationToken)
          }
          yield authenticationToken
        else ApplicativeError[F, Throwable].raiseError(AuthenticationException("Invalid password"))
      }

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
        ApplicativeError[F, Throwable].raiseError(AuthenticationException.MissingAuthenticationToken)
      }

  override def logout(secret: Secret): F[AuthenticationToken] =
    OptionT(keySpacedKeyValueStore.get(AuthenticationTokenKey(secret)))
      .semiflatTap {
        _ => keySpacedKeyValueStore.remove(AuthenticationTokenKey(secret))
      }
      .getOrElseF[AuthenticationToken] {
        ApplicativeError[F, Throwable].raiseError(AuthenticationException.MissingAuthenticationToken)
      }
}
