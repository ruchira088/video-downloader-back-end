package com.ruchij.api.daos.credentials

import cats.effect.IO
import cats.implicits._
import com.ruchij.api.daos.credentials.DoobieCredentialsDao
import com.ruchij.api.daos.credentials.models.Credentials
import com.ruchij.api.daos.credentials.models.Credentials.HashedPassword
import com.ruchij.api.daos.user.DoobieUserDao
import com.ruchij.api.daos.user.models.{Email, Role, User}
import com.ruchij.core.external.embedded.EmbeddedCoreResourcesProvider
import com.ruchij.core.test.IOSupport.runIO
import org.joda.time.DateTime
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.ExecutionContext.Implicits.global

class DoobieCredentialsDaoSpec extends AnyFlatSpec with Matchers with OptionValues {

  "DoobieCredentialsDao" should "perform CRUD operations for Credentials in the database" in runIO {
    new EmbeddedCoreResourcesProvider[IO].transactor.use { implicit transactor =>
      val timestamp = new DateTime(2021, 10, 17, 22, 24, 10, 123)

      val user = User("my-user-id", timestamp, "John", "Doe", Email("john.doe@ruchij.com"), Role.Admin)
      val credentials = Credentials("my-user-id", timestamp, HashedPassword("hashed-password"))

      for {
        insertResult <- transactor {
          DoobieUserDao
            .insert(user)
            .product(DoobieCredentialsDao.insert(credentials))
            .map {
              case (userInsertionResult, credentialInsertionResult) =>
                userInsertionResult + credentialInsertionResult
            }
        }

        _ <- IO.delay(insertResult mustBe 2)

        maybeUserCredentials <- transactor(DoobieCredentialsDao.findCredentialsByUserId("my-user-id"))

        _ <- IO.delay {
          maybeUserCredentials mustBe Some(credentials)
        }

        updateResult <- transactor {
          DoobieCredentialsDao.update {
            credentials
              .copy(hashedPassword = HashedPassword("new-credentials"), lastUpdatedAt = timestamp.plusSeconds(10))
          }
        }

        _ <- IO.delay(updateResult mustBe 1)

        noUpdateResult <- transactor {
          DoobieCredentialsDao.update {
            Credentials("no-user-id", timestamp, HashedPassword("hashed-password"))
          }
        }

        _ <- IO.delay(noUpdateResult mustBe 0)

        maybeUpdatedCredentials <- transactor(DoobieCredentialsDao.findCredentialsByUserId("my-user-id"))

        updatedCredentials <- IO.delay(maybeUpdatedCredentials.value)

        _ <- IO.delay {
          updatedCredentials.userId mustBe "my-user-id"
          updatedCredentials.lastUpdatedAt mustBe timestamp.plusSeconds(10)
          updatedCredentials.hashedPassword mustBe HashedPassword("new-credentials")
        }

        noDeletionResult <- transactor(DoobieCredentialsDao.deleteByUserId("missing-user-id"))

        _ <- IO.delay(noDeletionResult mustBe 0)

        deletionResult <- transactor(DoobieCredentialsDao.deleteByUserId("my-user-id"))

        _ <- IO.delay(deletionResult mustBe 1)

        maybeDeletedUser <- transactor(DoobieCredentialsDao.findCredentialsByUserId("my-user-id"))

        _ <- IO.delay(maybeDeletedUser mustBe None)

      } yield (): Unit
    }
  }

}
