package com.ruchij.api.daos.user

import cats.effect.IO
import com.ruchij.api.daos.user.models.{Email, Role, User}
import com.ruchij.core.external.embedded.EmbeddedExternalServiceProvider
import com.ruchij.core.test.IOSupport.runIO
import org.joda.time.DateTime
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.ExecutionContext.Implicits.global

class DoobieUserDaoSpec extends AnyFlatSpec with Matchers {

  "DoobieUserDao" should "support CRUD operations" in runIO {
    val timestamp = new DateTime(2021, 10, 18, 9, 34, 8, 123)
    val user = User("my-user-id", timestamp, "Ruchira", "Jayasekara", Email("user@ruchij.com"), Role.Admin)

    new EmbeddedExternalServiceProvider[IO].transactor.use { implicit transactor =>
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
