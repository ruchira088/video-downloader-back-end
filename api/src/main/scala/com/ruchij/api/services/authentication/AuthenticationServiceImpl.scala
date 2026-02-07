package com.ruchij.api.services.authentication

import cats.data.OptionT
import cats.effect.kernel.Async
import cats.implicits._
import cats.{Applicative, MonadThrow, ~>}
import com.ruchij.api.daos.credentials.CredentialsDao
import com.ruchij.api.daos.user.UserDao
import com.ruchij.api.daos.user.models.{Email, User}
import com.ruchij.api.exceptions.AuthenticationException
import com.ruchij.api.services.authentication.AuthenticationService.{Password, Secret}
import com.ruchij.api.services.authentication.models.AuthenticationToken
import com.ruchij.api.services.authentication.models.AuthenticationToken.AuthenticationTokenKey
import com.ruchij.api.services.hashing.PasswordHashingService
import com.ruchij.core.exceptions.ResourceNotFoundException
import com.ruchij.core.kv.KeySpacedKeyValueStore
import com.ruchij.core.types.{Clock, RandomGenerator}

import scala.concurrent.duration.FiniteDuration

class AuthenticationServiceImpl[F[_]: Async: Clock: RandomGenerator[*[_], Secret], G[_]: MonadThrow](
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
            MonadThrow[G].raiseError(AuthenticationException("Non-existing user"))
          }
      }

      isAuthenticated <- passwordHashingService.checkPassword(password, credentials.hashedPassword)
      _ <- if (isAuthenticated) Applicative[F].unit
      else MonadThrow[F].raiseError(AuthenticationException("Invalid password"))

      timestamp <- Clock[F].timestamp
      secret <- RandomGenerator[F, Secret].generate

      authenticationToken = AuthenticationToken(
        credentials.userId,
        secret,
        timestamp.plusMillis(authSessionDuration.toMillis),
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
            timestamp <- Clock[F].timestamp
            _ <- if (timestamp.isBefore(expiresAt)) Applicative[F].unit
            else
              keySpacedKeyValueStore
                .remove(AuthenticationTokenKey(secret))
                .productR {
                  MonadThrow[F].raiseError {
                    AuthenticationException(s"Authentication token expired at $expiresAt")
                  }
                }

            authenticationToken = AuthenticationToken(
              userId,
              secret,
              timestamp.plusMillis(authSessionDuration.toMillis),
              issuedAt,
              renewals + 1
            )
            _ <- keySpacedKeyValueStore.put(AuthenticationTokenKey(secret), authenticationToken)

            user <- getUserById(authenticationToken.userId)
          } yield (authenticationToken, user)
      }
      .getOrElseF[(AuthenticationToken, User)] {
        MonadThrow[F].raiseError(AuthenticationException.MissingAuthenticationToken)
      }

  override def logout(secret: Secret): F[User] =
    OptionT(keySpacedKeyValueStore.get(AuthenticationTokenKey(secret)))
      .productL {
        OptionT.liftF(keySpacedKeyValueStore.remove(AuthenticationTokenKey(secret)))
      }
      .flatMapF { authenticationToken =>
        for {
          timestamp <- Clock[F].timestamp
          maybeAuthenticationToken =
            if (authenticationToken.expiresAt.isAfter(timestamp)) Some(authenticationToken) else None
        } yield maybeAuthenticationToken
      }
      .getOrElseF[AuthenticationToken] {
        MonadThrow[F].raiseError(AuthenticationException.MissingAuthenticationToken)
      }
      .flatMap(authenticationToken => getUserById(authenticationToken.userId))

  private def getUserById(userId: String): F[User] =
    OptionT(transaction(userDao.findById(userId)))
      .getOrRaise(ResourceNotFoundException(s"User not found id=$userId"))
}
