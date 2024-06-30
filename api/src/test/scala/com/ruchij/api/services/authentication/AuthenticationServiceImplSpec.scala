package com.ruchij.api.services.authentication

import cats.effect.IO
import com.ruchij.api.daos.credentials.DoobieCredentialsDao
import com.ruchij.api.daos.permission.DoobieVideoPermissionDao
import com.ruchij.api.daos.resettoken.DoobieCredentialsResetTokenDao
import com.ruchij.api.daos.title.DoobieVideoTitleDao
import com.ruchij.api.daos.user.DoobieUserDao
import com.ruchij.api.daos.user.models.Email
import com.ruchij.api.external.ApiResourcesProvider
import com.ruchij.api.external.containers.ContainerApiResourcesProvider
import com.ruchij.api.services.authentication.AuthenticationService.{Password, Secret}
import com.ruchij.api.services.authentication.models.AuthenticationToken
import com.ruchij.api.services.authentication.models.AuthenticationToken.{AuthenticationKeySpace, AuthenticationTokenKey}
import com.ruchij.api.services.hashing.BCryptPasswordHashingService
import com.ruchij.api.services.user.{UserService, UserServiceImpl}
import com.ruchij.api.test.matchers.haveDateTime
import com.ruchij.core.kv.{KeySpacedKeyValueStore, RedisKeyValueStore}
import com.ruchij.core.test.IOSupport.{IOWrapper, runIO}
import com.ruchij.core.types.{JodaClock, RandomGenerator}
import doobie.free.connection.ConnectionIO
import org.joda.time.DateTime
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

class AuthenticationServiceImplSpec extends AnyFlatSpec with Matchers with MockFactory {

  def runTest(
    testCase: (RandomGenerator[IO, Secret], JodaClock[IO], UserService[IO], AuthenticationService[IO]) => IO[Unit]
  ): Unit =
    runIO {
      val apiResourcesProvider: ApiResourcesProvider[IO] = new ContainerApiResourcesProvider[IO]

      apiResourcesProvider.redisConfiguration
        .flatMap(redisConfig => RedisKeyValueStore.create[IO](redisConfig))
        .map { redisKeyValueStore =>
          new KeySpacedKeyValueStore[IO, AuthenticationTokenKey, AuthenticationToken](
            AuthenticationKeySpace,
            redisKeyValueStore
          )
        }
        .use { keySpacedKeyValueStore =>
          apiResourcesProvider.transactor.use { implicit transactor =>
            implicit val jodaClock: JodaClock[IO] = mock[JodaClock[IO]]
            implicit val randomGenerator: RandomGenerator[IO, Secret] = mock[RandomGenerator[IO, Secret]]

            val passwordHashingService = new BCryptPasswordHashingService[IO]

            val authenticationService =
              new AuthenticationServiceImpl[IO, ConnectionIO](
                keySpacedKeyValueStore,
                passwordHashingService,
                DoobieUserDao,
                DoobieCredentialsDao,
                20 seconds
              )

            val userService =
              new UserServiceImpl[IO, ConnectionIO](
                passwordHashingService,
                DoobieUserDao,
                DoobieCredentialsDao,
                DoobieCredentialsResetTokenDao,
                DoobieVideoTitleDao,
                DoobieVideoPermissionDao
              )

            testCase(randomGenerator, jodaClock, userService, authenticationService)
          }
        }
    }

  "Authentication service" should "go through authentication flow" in
    runTest { (secretGenerator, jodaClock, userService, authenticationService) =>
      val timestamp = new DateTime(2022, 10, 8, 19, 0)

      for {
        _ <- IO.delay { (() => jodaClock.timestamp).expects().returns(IO.pure(timestamp)) }

        email <- RandomGenerator[IO, UUID].generate.map(uuid => Email(s"$uuid@ruchij.com"))
        password = Password("my-password")
        user <- userService.create("Ruchira", "Jayasekara", email, password)
        secret <- RandomGenerator[IO, UUID].generate.map(uuid => Secret(uuid.toString))

        _ <- IO.delay { (() => secretGenerator.generate).expects().returning(IO.pure(secret)) }
        _ <- IO.delay { (() => jodaClock.timestamp).expects().returns(IO.pure(timestamp)) }

        authenticationTokenOne <- authenticationService.login(email, password)

        _ <- IO.delay {
          authenticationTokenOne.secret mustBe secret
          authenticationTokenOne.issuedAt must haveDateTime(timestamp)
          authenticationTokenOne.renewals mustBe 0
          authenticationTokenOne.expiresAt must haveDateTime(timestamp.plusSeconds(20))
        }

        _ <- IO.delay {
          (() => jodaClock.timestamp).expects().returns(IO.pure(timestamp.plusSeconds(10))).anyNumberOfTimes()
        }

        (authenticationTokenTwo, authUser) <- authenticationService.authenticate(secret)

        _ <- IO.delay {
          authUser mustBe user
          authenticationTokenTwo.secret mustBe secret
          authenticationTokenTwo.issuedAt must haveDateTime(timestamp)
          authenticationTokenTwo.renewals mustBe 1
          authenticationTokenTwo.expiresAt must haveDateTime(timestamp.plusSeconds(30))
        }

        _ <- authenticationService.logout(secret)

        authenticationException <- authenticationService.authenticate(secret).error

        _ <- IO.delay {
          authenticationException.getMessage mustBe "Authentication cookie/token not found"
        }

      } yield (): Unit
    }

  it should "return an exception if the password is incorrect" in runTest { (_, _, _, authenticationService) =>
    authenticationService
      .login(Email("me@ruchij.com"), Password("wrong-password"))
      .error
      .flatMap { authenticationException =>
        IO.delay {
          authenticationException.getMessage mustBe "Invalid password"
        }
      }
  }

  it should "return an exception if non-existing user" in runTest { (_, _, _, authenticationService) =>
    authenticationService
      .login(Email("non.existing.user@ruchij.com"), Password("password"))
      .error
      .flatMap { authenticationException =>
        IO.delay {
          authenticationException.getMessage mustBe "Non-existing user"
        }
      }
  }

  it should "return an exception if the authentication token is missing" in runTest {
    (_, _, _, authenticationService) =>
      authenticationService
        .authenticate(Secret("missing-secret"))
        .error
        .flatMap { authenticationException =>
          IO.delay {
            authenticationException.getMessage mustBe "Authentication cookie/token not found"
          }
        }
  }

  it should "return an exception if the authentication token is expired" in runTest {
    (secretGenerator, jodaClock, userService, authenticationService) =>
      val timestamp = new DateTime(2022, 10, 9, 10, 0)

      for {
        _ <- IO.delay { (() => jodaClock.timestamp).expects().returns(IO.pure(timestamp)) }

        email <- RandomGenerator[IO, UUID].generate.map(uuid => Email(s"$uuid@ruchij.com"))
        password = Password("my-password")
        _ <- userService.create("Ruchira", "Jayasekara", email, password)
        secret <- RandomGenerator[IO, UUID].generate.map(uuid => Secret(uuid.toString))

        _ <- IO.delay((() => secretGenerator.generate).expects().returning(IO.pure(secret)))
        _ <- IO.delay { (() => jodaClock.timestamp).expects().returns(IO.pure(timestamp)) }

        _ <- authenticationService.login(email, password)

        _ <- IO.delay { (() => jodaClock.timestamp).expects().returns(IO.pure(timestamp.plusSeconds(15))) }
        _ <- authenticationService.authenticate(secret)

        _ <- IO.delay { (() => jodaClock.timestamp).expects().returns(IO.pure(timestamp.plusSeconds(60))) }
        tokenExpiredException <- authenticationService.authenticate(secret).error

        _ <- IO.delay {
          tokenExpiredException.getMessage mustBe s"Authentication token expired at ${timestamp.plusSeconds(35)}"
        }

        missingTokenException <- authenticationService.authenticate(secret).error

        _ <- IO.delay { missingTokenException.getMessage mustBe "Authentication cookie/token not found" }
      } yield (): Unit
  }

}
