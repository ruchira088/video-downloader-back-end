package com.ruchij.api.services.user

import cats.effect.Clock
import cats.implicits._
import cats.{Applicative, ApplicativeError, Monad, MonadError, ~>}
import com.ruchij.api.config.AuthenticationConfiguration
import com.ruchij.api.config.AuthenticationConfiguration.{NoAuthenticationConfiguration, PasswordAuthenticationConfiguration}
import com.ruchij.api.daos.credentials.CredentialsDao
import com.ruchij.api.daos.credentials.models.Credentials
import com.ruchij.api.daos.user.UserDao
import com.ruchij.api.daos.user.models.{Email, Role, User}
import com.ruchij.api.exceptions.ResourceConflictException
import com.ruchij.api.services.authentication.AuthenticationService.Password
import com.ruchij.api.services.hashing.PasswordHashingService
import com.ruchij.core.types.{JodaClock, RandomGenerator}

import java.util.UUID

class UserServiceImpl[F[+ _]: RandomGenerator[*[_], UUID]: MonadError[*[_], Throwable]: Clock, G[_]: Monad](
  passwordHashingService: PasswordHashingService[F],
  userDao: UserDao[G],
  credentialsDao: CredentialsDao[G],
  authenticationConfiguration: AuthenticationConfiguration
)(implicit transaction: G ~> F)
    extends UserService[F] {

  override def create(firstName: String, lastName: String, email: Email, password: Password): F[User] =
    for {
      maybeUser <- transaction(userDao.findByEmail(email))
      _ <- maybeUser.fold[F[Unit]](Applicative[F].unit) { _ =>
        ApplicativeError[F, Throwable].raiseError(
          ResourceConflictException(s"User with email ${email.value} already exists")
        )
      }

      isAdmin <-
        authenticationConfiguration match {
          case NoAuthenticationConfiguration => Applicative[F].pure(true)

          case PasswordAuthenticationConfiguration(hashedPassword, _) =>
            passwordHashingService.checkPassword(password, hashedPassword)
        }

      hashedPassword <- passwordHashingService.hashPassword(password)
      timestamp <- JodaClock[F].timestamp

      userId <- RandomGenerator[F, UUID].generate.map(_.toString)
      role = if (isAdmin) Role.AdminRole else Role.UserRole
      user = User(userId, timestamp, firstName, lastName, email, role)
      credentials = Credentials(userId, timestamp, hashedPassword)

      _ <- transaction { userDao.insert(user).productL(credentialsDao.insert(credentials)) }

    } yield user

}
