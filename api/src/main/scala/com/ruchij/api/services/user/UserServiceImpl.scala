package com.ruchij.api.services.user

import cats.data.OptionT
import cats.implicits._
import cats.effect.Sync
import cats.{Applicative, ApplicativeError, MonadThrow, ~>}
import com.ruchij.api.daos.credentials.CredentialsDao
import com.ruchij.api.daos.credentials.models.Credentials
import com.ruchij.api.daos.playlist.PlaylistDao
import com.ruchij.api.daos.playlist.models.PlaylistSortBy
import com.ruchij.api.daos.resettoken.CredentialsResetTokenDao
import com.ruchij.api.daos.resettoken.models.CredentialsResetToken
import com.ruchij.api.daos.user.UserDao
import com.ruchij.api.daos.user.models.{Email, Role, User}
import com.ruchij.api.exceptions.{AuthorizationException, ResourceConflictException}
import com.ruchij.api.services.authentication.AuthenticationService.Password
import com.ruchij.api.services.fallback.FallbackSyncRequester
import com.ruchij.api.services.hashing.PasswordHashingService
import com.ruchij.core.daos.doobie.DoobieUtils.SingleUpdateOps
import com.ruchij.core.daos.permission.VideoPermissionDao
import com.ruchij.core.daos.resource.FileResourceDao
import com.ruchij.core.daos.title.VideoTitleDao
import com.ruchij.core.exceptions.{ResourceNotFoundException, ValidationException}
import com.ruchij.core.logging.Logger
import com.ruchij.core.services.models.Order
import com.ruchij.core.services.repository.RepositoryService
import com.ruchij.core.types.{Clock, RandomGenerator}

import java.sql.SQLException
import java.util.UUID

class UserServiceImpl[F[_]: RandomGenerator[*[_], UUID]: Sync: Clock, G[_]: MonadThrow](
  passwordHashingService: PasswordHashingService[F],
  userDao: UserDao[G],
  credentialsDao: CredentialsDao[G],
  credentialsResetTokenDao: CredentialsResetTokenDao[G],
  videoTitleDao: VideoTitleDao[G],
  videoPermissionDao: VideoPermissionDao[G],
  playlistDao: PlaylistDao[G],
  fileResourceDao: FileResourceDao[G],
  repositoryService: RepositoryService[F],
  fallbackSyncRequester: FallbackSyncRequester[F]
)(implicit transaction: G ~> F)
    extends UserService[F] {
  import UserServiceImpl.UniqueViolationSqlState

  private val logger = Logger[UserServiceImpl[F, G]]

  override def create(firstName: String, lastName: String, email: Email, password: Password): F[User] =
    for {
      maybeUser <- transaction(userDao.findByEmail(email))
      _ <- maybeUser.fold[F[Unit]](Applicative[F].unit) { _ =>
        ApplicativeError[F, Throwable].raiseError(emailConflict(email))
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
        }.adaptError {
          // A concurrent sign-up with the same email can pass the check above, leaving the unique index to catch it
          case sqlException: SQLException if sqlException.getSQLState == UniqueViolationSqlState => emailConflict(email)
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
            // The token is consumed before the password changes, and only the transaction that deletes it may go on,
            // so concurrent resets with the same token cannot both succeed
            .flatMapF { _ =>
              credentialsResetTokenDao.delete(userId, resetToken).map(deleted => (deleted == 1).guard[Option])
            }
            .semiflatMap(_ => credentialsDao.update(Credentials(userId, timestamp, hashedPassword)).one)
            .flatMapF(_ => userDao.findById(userId))
            .getOrElseF {
              ApplicativeError[G, Throwable].raiseError {
                ResourceNotFoundException("Reset password token in not valid")
              }
            }
        }
    }

  override def delete(userId: String, adminUser: User): F[User] =
    if (adminUser.role != Role.Admin)
      ApplicativeError[F, Throwable].raiseError {
        AuthorizationException(s"User does NOT have permission to delete user: $userId")
      }
    else if (adminUser.id == userId)
      ApplicativeError[F, Throwable].raiseError(ValidationException("Admins cannot delete their own account"))
    else
      transaction {
        for {
          user <- fetchUserById(userId)
          _ <- if (user.role == Role.Admin) ensureAnotherAdminRemains(userId) else Applicative[G].unit

          // The user's videos are collected before their permissions are deleted, so each can be re-synced after
          permissions <- videoPermissionDao.find(Some(userId), None)
          _ <- videoTitleDao.delete(None, Some(userId))
          _ <- videoPermissionDao.delete(Some(userId), None)
          _ <- credentialsDao.deleteByUserId(userId)

          // Deleting the user deletes their playlists, but not the album art the playlists refer to
          playlists <-
            playlistDao.search(None, Int.MaxValue, 0, Order.Ascending, PlaylistSortBy.CreatedAt, Some(userId))
          albumArts = playlists.flatMap(_.albumArt)
          _ <- userDao.deleteById(userId).one
          _ <- albumArts.traverse_(albumArt => fileResourceDao.deleteById(albumArt.id))
        } yield (user, permissions.map(_.scheduledVideoDownloadId).distinct, albumArts)
      }.flatMap { case (user, videoIds, albumArts) =>
        // The files are deleted once the database no longer refers to them. A failure only leaves a file behind.
        albumArts
          .traverse_ { albumArt =>
            repositoryService.delete(albumArt.path).void.handleErrorWith { throwable =>
              logger.warn[F](s"Unable to delete the album art file ${albumArt.path}: ${throwable.getMessage}")
            }
          }
          .productR(fallbackSyncRequester.requestAll(videoIds))
          .as(user)
      }

  /**
    * The admin rows are locked, so concurrent deletes of different admins run one after the other and the later one
    * sees the earlier one's delete, rather than each leaving the other as the last admin.
    */
  private def ensureAnotherAdminRemains(adminId: String): G[Unit] =
    userDao.lockAdminIds.flatMap { adminIds =>
      if (adminIds.exists(_ != adminId)) Applicative[G].unit
      else
        ApplicativeError[G, Throwable].raiseError {
          ResourceConflictException("Unable to delete the last remaining admin")
        }
    }

  private def emailConflict(email: Email): ResourceConflictException =
    ResourceConflictException(s"User with email ${email.value} already exists")

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

object UserServiceImpl {
  /** The SQLSTATE that both PostgreSQL and H2 report for a unique constraint violation */
  val UniqueViolationSqlState = "23505"
}
