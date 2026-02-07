package com.ruchij.api.services.user

import cats.data.OptionT
import cats.implicits._
import cats.{Applicative, ApplicativeError, MonadThrow, ~>}
import com.ruchij.api.daos.credentials.CredentialsDao
import com.ruchij.api.daos.credentials.models.Credentials
import com.ruchij.api.daos.resettoken.CredentialsResetTokenDao
import com.ruchij.api.daos.resettoken.models.CredentialsResetToken
import com.ruchij.api.daos.user.UserDao
import com.ruchij.api.daos.user.models.{Email, Role, User}
import com.ruchij.api.exceptions.{AuthorizationException, ResourceConflictException}
import com.ruchij.api.services.authentication.AuthenticationService.Password
import com.ruchij.api.services.hashing.PasswordHashingService
import com.ruchij.core.daos.doobie.DoobieUtils.SingleUpdateOps
import com.ruchij.core.daos.permission.VideoPermissionDao
import com.ruchij.core.daos.title.VideoTitleDao
import com.ruchij.core.exceptions.ResourceNotFoundException
import com.ruchij.core.types.{Clock, RandomGenerator}

import java.util.UUID

class UserServiceImpl[F[_]: RandomGenerator[*[_], UUID]: MonadThrow: Clock, G[_]: MonadThrow](
  passwordHashingService: PasswordHashingService[F],
  userDao: UserDao[G],
  credentialsDao: CredentialsDao[G],
  credentialsResetTokenDao: CredentialsResetTokenDao[G],
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
      timestamp <- Clock[F].timestamp

      userId <- RandomGenerator[F, UUID].generate.map(_.toString)
      user = User(userId, timestamp, firstName, lastName, email, Role.User)
      credentials = Credentials(userId, timestamp, hashedPassword)

      _ <-
        transaction {
          userDao.insert(user).one
            .productL(credentialsDao.insert(credentials).one)
        }

    } yield user

  override def getById(userId: String): F[User] = transaction(fetchUserById(userId))

  override def getByEmail(email: Email): F[User] = transaction(fetchUserByEmail(email))

  override def forgotPassword(email: Email): F[CredentialsResetToken] =
    for {
      token <- RandomGenerator[F, UUID].generate.map(_.toString)
      timestamp <- Clock[F].timestamp

      credentialsResetToken <- transaction {
        fetchUserByEmail(email)
          .flatMap { user =>
            val resetToken = CredentialsResetToken(user.id, timestamp, token)

            credentialsResetTokenDao.insert(resetToken).as(resetToken)
          }
      }
    }
    yield credentialsResetToken

  override def resetPassword(userId: String, resetToken: String, password: Password): F[User] =
    Clock[F].timestamp.product(passwordHashingService.hashPassword(password))
      .flatMap { case (timestamp, hashedPassword) =>
        transaction {
          OptionT(credentialsResetTokenDao.find(userId, resetToken))
            .filter { resetToken =>
              resetToken.createdAt.plusMillis(UserService.ResetTokenValidity.toMillis).isAfter(timestamp)
            }
            .semiflatMap(_ => credentialsDao.update(Credentials(userId, timestamp, hashedPassword)))
            .semiflatMap(_ => credentialsResetTokenDao.delete(userId, resetToken))
            .productR(OptionT(userDao.findById(userId)))
            .getOrElseF {
              ApplicativeError[G, Throwable].raiseError {
                ResourceNotFoundException("Reset password token in not valid")
              }
            }
        }
    }

  override def delete(userId: String, adminUser: User): F[User] =
    if (adminUser.role == Role.Admin)
      transaction {
        videoTitleDao.delete(None, Some(userId))
          .productR(videoPermissionDao.delete(Some(userId), None))
          .productR(credentialsDao.deleteByUserId(userId))
          .productR(fetchUserById(userId))
          .productL(userDao.deleteById(userId).one)
      }
    else ApplicativeError[F, Throwable].raiseError {
      AuthorizationException(s"User does NOT have permission to delete user: $userId")
    }

  private def fetchUserByEmail(email: Email): G[User] =
    OptionT(userDao.findByEmail(email))
      .getOrRaise(ResourceNotFoundException(s"User not found email=${email.value}"))

  private def fetchUserById(userId: String): G[User] =
    OptionT(userDao.findById(userId))
      .getOrElseF {
        ApplicativeError[G, Throwable].raiseError {
          ResourceNotFoundException(s"User id=$userId does NOT exist")
        }
      }
}
