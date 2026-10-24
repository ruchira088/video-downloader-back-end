package com.ruchij.api.daos.user

import cats.effect.IO
import com.ruchij.api.daos.user.models.{Email, Role, User}
import com.ruchij.core.test.{H2TestDatabase, PostgresTestDatabase, TestDatabase}
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.types.TimeUtils
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers


class H2DoobieUserDaoSpec extends DoobieUserDaoSpec with H2TestDatabase

class PostgresDoobieUserDaoSpec extends DoobieUserDaoSpec with PostgresTestDatabase

abstract class DoobieUserDaoSpec extends AnyFlatSpec with Matchers with TestDatabase {

  "DoobieUserDao" should "support CRUD operations" in runIO {
    val timestamp = TimeUtils.instantOf(2021, 10, 18, 9, 34, 8, 123)
    val user = User("my-user-id", timestamp, "Ruchira", "Jayasekara", Email("user@ruchij.com"), Role.Admin)

    transactor.use { implicit transactor =>
      for {
        insertionResult <- transactor(DoobieUserDao.insert(user))
        _ <- IO.delay(insertionResult mustBe 1)

        findByEmailResult <- transactor(DoobieUserDao.findByEmail(Email("user@ruchij.com")))
        _ <- IO.delay(findByEmailResult mustBe Some(user))

        findByIdResult <- transactor(DoobieUserDao.findById("my-user-id"))
        _ <- IO.delay(findByIdResult mustBe Some(user))

        deletionResult <- transactor(DoobieUserDao.deleteById("my-user-id"))
        _ <- IO.delay(deletionResult mustBe 1)

        emptyResult <- transactor(DoobieUserDao.findById("my-user-id"))
        _ <- IO.delay(emptyResult mustBe None)
      }
      yield (): Unit
    }

  }

}
