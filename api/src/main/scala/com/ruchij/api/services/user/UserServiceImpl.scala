package com.ruchij.api.services.user

import cats.data.OptionT
import cats.effect.Clock
import cats.implicits._
import cats.{Applicative, ApplicativeError, MonadError, ~>}
import com.ruchij.api.daos.credentials.CredentialsDao
import com.ruchij.api.daos.credentials.models.Credentials
import com.ruchij.api.daos.permission.VideoPermissionDao
import com.ruchij.api.daos.user.UserDao
import com.ruchij.api.daos.user.models.{Email, Role, User}
import com.ruchij.api.exceptions.{AuthorizationException, ResourceConflictException}
import com.ruchij.api.services.authentication.AuthenticationService.Password
import com.ruchij.api.services.hashing.PasswordHashingService
import com.ruchij.core.daos.doobie.DoobieUtils.SingleUpdateOps
import com.ruchij.api.daos.title.VideoTitleDao
import com.ruchij.core.exceptions.ResourceNotFoundException
import com.ruchij.core.types.{JodaClock, RandomGenerator}

import java.util.UUID

class UserServiceImpl[F[+ _]: RandomGenerator[*[_], UUID]: MonadError[*[_], Throwable]: Clock, G[_]: MonadError[*[_], Throwable]](
  passwordHashingService: PasswordHashingService[F],
  userDao: UserDao[G],
  credentialsDao: CredentialsDao[G],
  videoTitleDao: VideoTitleDao[G],
  videoPermissionDao: VideoPermissionDao[G]
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

      hashedPassword <- passwordHashingService.hashPassword(password)
      timestamp <- JodaClock[F].timestamp

      userId <- RandomGenerator[F, UUID].generate.map(_.toString)
      user = User(userId, timestamp, firstName, lastName, email, Role.User)
      credentials = Credentials(userId, timestamp, hashedPassword)

      _ <-
        transaction {
          userDao.insert(user).one
            .productL(credentialsDao.insert(credentials).one)
        }

    } yield user

  override def delete(userId: String, adminUser: User): F[User] =
    if (adminUser.role == Role.Admin)
      transaction {
        videoTitleDao.delete(None, Some(userId))
          .productR(videoPermissionDao.delete(Some(userId), None))
          .productR(credentialsDao.deleteByUserId(userId))
          .productR {
            OptionT(userDao.findById(userId))
              .getOrElseF {
                ApplicativeError[G, Throwable].raiseError {
                  ResourceNotFoundException(s"User id=$userId does NOT exist")
                }
              }
          }
          .productL(userDao.deleteById(userId).one)
      }
    else ApplicativeError[F, Throwable].raiseError {
      AuthorizationException(s"User does NOT have permission to delete user: $userId")
    }
}
