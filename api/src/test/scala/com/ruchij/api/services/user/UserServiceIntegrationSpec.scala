package com.ruchij.api.services.user

import cats.effect.IO
import cats.implicits._
import cats.~>
import com.ruchij.api.daos.credentials.DoobieCredentialsDao
import com.ruchij.api.daos.playlist.DoobiePlaylistDao
import com.ruchij.api.daos.playlist.models.Playlist
import com.ruchij.api.daos.resettoken.DoobieCredentialsResetTokenDao
import com.ruchij.api.daos.user.models.{Email, User}
import com.ruchij.api.daos.user.{DoobieUserDao, UserDao}
import com.ruchij.api.exceptions.ResourceConflictException
import com.ruchij.api.services.authentication.AuthenticationService.Password
import com.ruchij.api.services.fallback.FallbackSyncRequester
import com.ruchij.api.services.hashing.BCryptPasswordHashingService
import com.ruchij.api.test.data.ApiTestData
import com.ruchij.core.daos.doobie.DoobieCustomMappings.instantPut
import com.ruchij.core.daos.permission.DoobieVideoPermissionDao
import com.ruchij.core.daos.resource.DoobieFileResourceDao
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.title.DoobieVideoTitleDao
import com.ruchij.core.daos.video.DoobieVideoDao
import com.ruchij.core.daos.videowatchhistory.DoobieVideoWatchHistoryDao
import com.ruchij.core.daos.videowatchhistory.models.VideoWatchHistory
import com.ruchij.core.exceptions.ValidationException
import com.ruchij.core.external.CoreResourcesProvider
import com.ruchij.core.external.containers.ContainerCoreResourcesProvider
import com.ruchij.core.external.embedded.EmbeddedCoreResourcesProvider
import com.ruchij.core.services.repository.InMemoryRepositoryService
import com.ruchij.core.test.IOSupport.{IOWrapper, runIO}
import com.ruchij.core.test.data.CoreTestData
import doobie.free.connection.ConnectionIO
import doobie.implicits._
import org.http4s.MediaType
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

/** Runs UserServiceImpl against real DAOs, on both H2 (PostgreSQL mode) and PostgreSQL. */
class UserServiceIntegrationSpec extends AnyFlatSpec with Matchers {

  private val providers: List[(String, () => CoreResourcesProvider[IO])] =
    List(
      "H2" -> (() => new EmbeddedCoreResourcesProvider[IO]),
      "PostgreSQL" -> (() => new ContainerCoreResourcesProvider[IO])
    )

  /** Simulates two sign-ups racing past the email check, so that only the database can catch the duplicate */
  private object RacingUserDao extends UserDao[ConnectionIO] {
    override def insert(user: User): ConnectionIO[Int] = DoobieUserDao.insert(user)
    override def findByEmail(email: Email): ConnectionIO[Option[User]] = Option.empty[User].pure[ConnectionIO]
    override def findById(userId: String): ConnectionIO[Option[User]] = DoobieUserDao.findById(userId)
    override def deleteById(userId: String): ConnectionIO[Int] = DoobieUserDao.deleteById(userId)
    override def lockAdminIds: ConnectionIO[List[String]] = DoobieUserDao.lockAdminIds
  }

  private def userService(
    userDao: UserDao[ConnectionIO] = DoobieUserDao,
    files: ConcurrentHashMap[String, List[Byte]] = new ConcurrentHashMap[String, List[Byte]]()
  )(implicit transaction: ConnectionIO ~> IO): UserServiceImpl[IO, ConnectionIO] =
    new UserServiceImpl[IO, ConnectionIO](
      new BCryptPasswordHashingService[IO],
      userDao,
      DoobieCredentialsDao,
      DoobieCredentialsResetTokenDao,
      DoobieVideoTitleDao,
      DoobieVideoPermissionDao,
      new DoobiePlaylistDao(DoobieFileResourceDao, DoobieVideoDao),
      DoobieFileResourceDao,
      new InMemoryRepositoryService[IO](files),
      FallbackSyncRequester.noOp[IO]
    )

  private def runTest(provider: () => CoreResourcesProvider[IO])(test: (ConnectionIO ~> IO) => IO[Any]): Unit =
    runIO {
      provider().transactor.use { transaction =>
        transaction(ApiTestData.setUpData).productR(test(transaction)).void
      }
    }

  // The admin that the migrations create
  private val SeededAdminId = "648f12b1-b207-4db2-9bef-1e20cd23cdf1"

  private def count(table: String, userId: String): ConnectionIO[Int] =
    (fr"SELECT COUNT(*) FROM" ++ doobie.Fragment.const(table) ++ fr"WHERE user_id = $userId").query[Int].unique

  providers.foreach {
    case (databaseName, provider) =>
      s"delete ($databaseName)" should "delete a user who has playlists, watch history and reset tokens" in
        runTest(provider) { implicit transaction =>
          val service = userService()
          val playlistDao = new DoobiePlaylistDao(DoobieFileResourceDao, DoobieVideoDao)
          val userId = ApiTestData.NormalUser.id

          for {
            _ <- transaction {
              playlistDao.insert(
                Playlist(
                  "playlist",
                  userId,
                  CoreTestData.Timestamp,
                  "Mine",
                  None,
                  List(CoreTestData.YouTubeVideo),
                  None
                )
              ) *>
                DoobieVideoWatchHistoryDao.insert(
                  VideoWatchHistory(
                    "history",
                    userId,
                    CoreTestData.YouTubeVideo.videoMetadata.id,
                    CoreTestData.Timestamp,
                    CoreTestData.Timestamp,
                    1.minute
                  )
                )
            }
            _ <- service.forgotPassword(ApiTestData.NormalUser.email)

            deleted <- service.delete(userId, ApiTestData.AdminUser)

            remaining <- transaction {
              List("playlist", "video_watch_history", "credentials_reset_token").traverse(table => count(table, userId))
            }
            playlistVideos <- transaction {
              sql"SELECT COUNT(*) FROM playlist_video WHERE playlist_id = 'playlist'".query[Int].unique
            }
            maybeUser <- transaction(DoobieUserDao.findById(userId))
          } yield {
            deleted.id mustBe userId
            remaining mustBe List(0, 0, 0)
            playlistVideos mustBe 0
            maybeUser mustBe None
          }
      }

      it should "delete the album art of the user's playlists" in runTest(provider) { implicit transaction =>
        val files = new ConcurrentHashMap[String, List[Byte]]()
        val albumArt = FileResource("album-art", CoreTestData.Timestamp, "images/album-art.png", MediaType.image.png, 4)
        val userId = ApiTestData.NormalUser.id

        for {
          _ <- IO.delay(files.put(albumArt.path, List[Byte](1, 2, 3, 4)))
          _ <- transaction {
            DoobieFileResourceDao.insert(albumArt) *>
              new DoobiePlaylistDao(DoobieFileResourceDao, DoobieVideoDao)
                .insert(Playlist("playlist", userId, CoreTestData.Timestamp, "Mine", None, Nil, Some(albumArt)))
          }

          _ <- userService(files = files).delete(userId, ApiTestData.AdminUser)

          resource <- transaction(DoobieFileResourceDao.getById(albumArt.id))
        } yield {
          resource mustBe None
          files.containsKey(albumArt.path) mustBe false
        }
      }

      it should "not let an admin delete their own account" in runTest(provider) { implicit transaction =>
        for {
          error <- userService().delete(ApiTestData.AdminUser.id, ApiTestData.AdminUser).error
          maybeAdmin <- transaction(DoobieUserDao.findById(ApiTestData.AdminUser.id))
        } yield {
          error mustBe a[ValidationException]
          maybeAdmin mustBe Some(ApiTestData.AdminUser)
        }
      }

      it should "not delete the last remaining admin" in runTest(provider) { implicit transaction =>
        // An admin whose own account was deleted after they signed in
        val formerAdmin = ApiTestData.AdminUser.copy(id = "former-admin", email = Email("former-admin@ruchij.com"))

        for {
          _ <- userService().delete(SeededAdminId, ApiTestData.AdminUser)
          error <- userService().delete(ApiTestData.AdminUser.id, formerAdmin).error
          maybeAdmin <- transaction(DoobieUserDao.findById(ApiTestData.AdminUser.id))
        } yield {
          error mustBe a[ResourceConflictException]
          maybeAdmin mustBe Some(ApiTestData.AdminUser)
        }
      }

      it should "keep an admin when two admins delete each other concurrently" in runTest(provider) {
        implicit transaction =>
          val otherAdmin = ApiTestData.AdminUser.copy(id = "other-admin", email = Email("other-admin@ruchij.com"))
          val service = userService()

          for {
            _ <- service.delete(SeededAdminId, ApiTestData.AdminUser)
            _ <- transaction(DoobieUserDao.insert(otherAdmin))
            results <- List(
              service.delete(otherAdmin.id, ApiTestData.AdminUser),
              service.delete(ApiTestData.AdminUser.id, otherAdmin)
            ).parTraverse(_.attempt)
            admins <- transaction(sql"SELECT COUNT(*) FROM api_user WHERE role = 'Admin'".query[Int].unique)
          } yield {
            results.count(_.isRight) mustBe 1
            admins mustBe 1
          }
      }

      s"create ($databaseName)" should "reject a duplicate email even when the email check is raced" in
        runTest(provider) { implicit transaction =>
          val service = userService(RacingUserDao)

          for {
            _ <- service.create("Jane", "Doe", Email("jane.doe@example.com"), Password("password-1"))
            error <- service.create("Janet", "Doe", Email("jane.doe@example.com"), Password("password-2")).error
            users <- transaction {
              sql"SELECT COUNT(*) FROM api_user WHERE email = 'jane.doe@example.com'".query[Int].unique
            }
          } yield {
            error mustBe a[ResourceConflictException]
            error.getMessage mustBe "User with email jane.doe@example.com already exists"
            users mustBe 1
          }
      }

      s"resetPassword ($databaseName)" should "accept a reset token only once, even when used concurrently" in
        runTest(provider) { implicit transaction =>
          val service = userService()
          val userId = ApiTestData.NormalUser.id

          for {
            // setUpData does not create credentials, so the user needs some before a reset
            _ <- transaction {
              sql"""
                INSERT INTO credentials (user_id, last_updated_at, hashed_password)
                  VALUES ($userId, ${CoreTestData.Timestamp}, 'hash')
              """.update.run
            }
            resetToken <- service.forgotPassword(ApiTestData.NormalUser.email)

            results <- List("password-a", "password-b").parTraverse { password =>
              service.resetPassword(userId, resetToken.token, Password(password)).attempt
            }

            reuse <- service.resetPassword(userId, resetToken.token, Password("password-c")).attempt
          } yield {
            results.count(_.isRight) mustBe 1
            reuse.isLeft mustBe true
          }
      }
  }
}
