package com.ruchij.api.services.authentication

import cats.data.OptionT
import cats.effect.{Clock, ContextShift, Sync}
import cats.implicits._
import cats.{Applicative, ApplicativeError, MonadError, ~>}
import com.ruchij.api.daos.credentials.CredentialsDao
import com.ruchij.api.daos.user.UserDao
import com.ruchij.api.daos.user.models.{Email, User}
import com.ruchij.api.exceptions.AuthenticationException
import com.ruchij.api.services.authentication.AuthenticationService.{Password, Secret}
import com.ruchij.api.services.authentication.models.AuthenticationToken
import com.ruchij.api.services.authentication.models.AuthenticationToken.AuthenticationTokenKey
import com.ruchij.api.services.hashing.PasswordHashingService
import com.ruchij.core.kv.KeySpacedKeyValueStore
import com.ruchij.core.types.{JodaClock, RandomGenerator}

import scala.concurrent.duration.FiniteDuration

class AuthenticationServiceImpl[F[+ _]: Sync: ContextShift: Clock: RandomGenerator[*[_], Secret], G[_]: MonadError[*[_], Throwable]](
  keySpacedKeyValueStore: KeySpacedKeyValueStore[F, AuthenticationTokenKey, AuthenticationToken],
  passwordHashingService: PasswordHashingService[F],
  userDao: UserDao[G],
  credentialsDao: CredentialsDao[G],
  authSessionDuration: FiniteDuration
)(implicit transaction: G ~> F)
    extends AuthenticationService[F] {

  override def login(email: Email, password: Password): F[AuthenticationToken] =
    for {
      credentials <- transaction {
        OptionT(userDao.findByEmail(email))
          .flatMapF(user => credentialsDao.findCredentialsByUserId(user.id))
          .getOrElseF {
            ApplicativeError[G, Throwable].raiseError(AuthenticationException("Non-existing user"))
          }
      }

      isAuthenticated <- passwordHashingService.checkPassword(password, credentials.hashedPassword)
      _ <- if (isAuthenticated) Applicative[F].unit
      else ApplicativeError[F, Throwable].raiseError(AuthenticationException("Invalid password"))

      timestamp <- JodaClock[F].timestamp
      secret <- RandomGenerator[F, Secret].generate

      authenticationToken = AuthenticationToken(
        credentials.userId,
        secret,
        timestamp.plus(authSessionDuration.toMillis),
        timestamp,
        0
      )
      _ <- keySpacedKeyValueStore.put(AuthenticationTokenKey(secret), authenticationToken)

    } yield authenticationToken

  override def authenticate(secret: Secret): F[(AuthenticationToken, User)] =
    OptionT(keySpacedKeyValueStore.get(AuthenticationTokenKey(secret)))
      .semiflatMap {
        case AuthenticationToken(userId, secret, expiresAt, issuedAt, renewals) =>
          for {
            timestamp <- JodaClock[F].timestamp
            _ <- if (timestamp.isBefore(expiresAt)) Applicative[F].unit
            else
              keySpacedKeyValueStore
                .remove(AuthenticationTokenKey(secret))
                .productR {
                  ApplicativeError[F, Throwable].raiseError {
                    AuthenticationException(s"Authentication token expired at $expiresAt")
                  }
                }

            authenticationToken = AuthenticationToken(
              userId,
              secret,
              timestamp.plus(authSessionDuration.toMillis),
              issuedAt,
              renewals + 1
            )
            _ <- keySpacedKeyValueStore.put(AuthenticationTokenKey(secret), authenticationToken)

            user <- OptionT(transaction(userDao.findById(userId)))
              .getOrElseF(ApplicativeError[F, Throwable].raiseError(AuthenticationException("User has been deleted")))
          } yield (authenticationToken, user)
      }
      .getOrElseF[(AuthenticationToken, User)] {
        ApplicativeError[F, Throwable].raiseError(AuthenticationException.MissingAuthenticationToken)
      }

  override def logout(secret: Secret): F[AuthenticationToken] =
    OptionT(keySpacedKeyValueStore.get(AuthenticationTokenKey(secret)))
      .productL {
        OptionT.liftF(keySpacedKeyValueStore.remove(AuthenticationTokenKey(secret)))
      }
      .flatMapF { authenticationToken =>
        for {
          timestamp <- JodaClock[F].timestamp
          maybeAuthenticationToken =
            if (authenticationToken.expiresAt.isAfter(timestamp)) Some(authenticationToken) else None
        } yield maybeAuthenticationToken
      }
      .getOrElseF[AuthenticationToken] {
        ApplicativeError[F, Throwable].raiseError(AuthenticationException.MissingAuthenticationToken)
      }
}
