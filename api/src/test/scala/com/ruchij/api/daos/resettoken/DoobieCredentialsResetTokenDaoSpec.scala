package com.ruchij.api.daos.resettoken

import cats.effect.IO
import cats.implicits._
import com.ruchij.api.daos.resettoken.models.CredentialsResetToken
import com.ruchij.api.daos.user.DoobieUserDao
import com.ruchij.api.daos.user.models.{Email, Role, User}
import com.ruchij.core.test.{H2TestDatabase, PostgresTestDatabase, TestDatabase}
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.types.TimeUtils
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers


class H2DoobieCredentialsResetTokenDaoSpec extends DoobieCredentialsResetTokenDaoSpec with H2TestDatabase

class PostgresDoobieCredentialsResetTokenDaoSpec extends DoobieCredentialsResetTokenDaoSpec with PostgresTestDatabase

abstract class DoobieCredentialsResetTokenDaoSpec extends AnyFlatSpec with Matchers with TestDatabase {

  "DoobieCredentialsResetTokenDao" should "perform CRUD operations" in runIO {
    transactor.use { implicit transactor =>
      val timestamp = TimeUtils.instantOf(2021, 10, 18, 18, 5, 44, 100)
      val user = User("my-user-id", timestamp, "John", "Doe", Email("john@ruchij.com"), Role.User)
      val credentialsResetToken = CredentialsResetToken("my-user-id", timestamp, "my-token")

      for {
        insertionResult <-
          transactor {
            DoobieUserDao.insert(user)
              .product(DoobieCredentialsResetTokenDao.insert(credentialsResetToken))
              .map { case (resultOne, resultTwo) => resultOne + resultTwo }
          }
        _ <- IO.delay(insertionResult mustBe 2)

        findResult <- transactor(DoobieCredentialsResetTokenDao.find("my-user-id", "my-token"))
        _ <- IO.delay(findResult mustBe Some(credentialsResetToken))

        noResult <- transactor(DoobieCredentialsResetTokenDao.find("another-user-id", "my-token"))
        _ <- IO.delay(noResult mustBe None)

        deletionResult <- transactor(DoobieCredentialsResetTokenDao.delete("my-user-id", "my-token"))
        _ <- IO.delay(deletionResult mustBe 1)

        anotherFindResult <- transactor(DoobieCredentialsResetTokenDao.find("my-user-id", "my-token"))
        _ <- IO.delay(anotherFindResult mustBe None)
      }
      yield (): Unit
    }
  }

}
