package com.ruchij.api.services.authentication

import cats.effect.{Clock, IO}
import cats.implicits._
import com.ruchij.api.config.AuthenticationConfiguration.{HashedPassword, PasswordAuthenticationConfiguration}
import com.ruchij.api.services.authentication.AuthenticationService.{Password, Secret}
import com.ruchij.api.services.authentication.models.AuthenticationToken
import com.ruchij.api.services.authentication.models.AuthenticationToken.{AuthenticationKeySpace, AuthenticationTokenKey}
import com.ruchij.core.kv.{InMemoryKeyValueStore, KeySpacedKeyValueStore}
import com.ruchij.core.test.IOSupport.{IOWrapper, runIO}
import com.ruchij.core.test.Providers.{blocker, contextShift}
import com.ruchij.core.types.RandomGenerator
import org.joda.time.DateTime
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

class AuthenticationServiceImplSpec extends AnyFlatSpec with Matchers with MockFactory {

  def runTest(testCase: (Long, UUID, Clock[IO], AuthenticationServiceImpl[IO]) => IO[Unit]): Unit =
    runIO {
      Clock
        .create[IO]
        .realTime(TimeUnit.MILLISECONDS)
        .product(IO.delay(UUID.randomUUID()))
        .flatMap {
          case (milliseconds, uuid) =>
            implicit val clock: Clock[IO] = mock[Clock[IO]]

            val keySpacedKeyValueStore = new KeySpacedKeyValueStore[IO, AuthenticationTokenKey, AuthenticationToken](
              AuthenticationKeySpace,
              new InMemoryKeyValueStore[IO]
            )

            val passwordAuthenticationConfiguration =
              PasswordAuthenticationConfiguration(
                HashedPassword("$2a$10$m5CQAirrrJKRqG3oalNSU.TUOn56v88isxMbNPi8cXXI35gY20hO."), // The password is "top-secret",
                30 seconds
              )

            implicit val randomGenerator: RandomGenerator[IO, Secret] =
              RandomGenerator[IO, Secret](Secret(uuid.toString))

            val authenticationService =
              new AuthenticationServiceImpl[IO](keySpacedKeyValueStore, passwordAuthenticationConfiguration, blocker)

            testCase(milliseconds, uuid, clock, authenticationService)
        }
    }

  "Authentication service" should "go through authentication flow" in runTest {
    (timestamp, uuid, clock, authenticationService) =>
      for {
        _ <- IO.delay { (clock.realTime _).expects(TimeUnit.MILLISECONDS).returning(IO.pure(timestamp)) }
        authenticationTokenOne <- authenticationService.login(Password("top-secret"))

        _ = {
          authenticationTokenOne.secret mustBe Secret(uuid.toString)
          authenticationTokenOne.issuedAt.getMillis mustBe timestamp
          authenticationTokenOne.renewals mustBe 0
          authenticationTokenOne.expiresAt.getMillis mustBe (timestamp + (30 seconds).toMillis)
        }

        _ <- IO.delay {
          (clock.realTime _).expects(TimeUnit.MILLISECONDS).returning(IO.pure(timestamp + (10 seconds).toMillis))
        }
        authenticationTokenTwo <- authenticationService.authenticate(Secret(uuid.toString))

        _ = {
          authenticationTokenTwo.secret mustBe Secret(uuid.toString)
          authenticationTokenTwo.issuedAt.getMillis mustBe timestamp
          authenticationTokenTwo.renewals mustBe 1
          authenticationTokenTwo.expiresAt.getMillis mustBe (timestamp + (40 seconds).toMillis)
        }

        _ <- authenticationService.logout(Secret(uuid.toString))

        authenticationException <- authenticationService.authenticate(Secret(uuid.toString)).error

        _ = {
          authenticationException.getMessage mustBe "Authentication cookie/token not found"
        }

      } yield (): Unit
  }

  it should "return an exception if the password is incorrect" in runTest { (_, _, _, authenticationService) =>
    authenticationService
      .login(Password("invalid-password"))
      .error
      .flatMap {
         authenticationException =>
          IO.delay {
            authenticationException.getMessage mustBe "Invalid password"
          }
      }
  }

  it should "return an exception if the authentication token is missing" in runTest {
    (_, _, _, authenticationService) =>
      authenticationService
        .authenticate(Secret("missing-secret"))
        .error
        .flatMap {
          authenticationException =>
            IO.delay {
              authenticationException.getMessage mustBe "Authentication cookie/token not found"
            }
        }
  }

  it should "return an exception if the authentication token is expired" in runTest {
    (timestamp, uuid, clock, authenticationService) =>
      for {
        _ <- IO.delay {
          (clock.realTime _).expects(TimeUnit.MILLISECONDS).returning(IO.pure(timestamp))
        }
        _ <- authenticationService.login(Password("top-secret"))

        _ <- IO.delay {
          (clock.realTime _).expects(TimeUnit.MILLISECONDS).returning(IO.pure(timestamp + (10 seconds).toMillis))
        }
        _ <- authenticationService.authenticate(Secret(uuid.toString))

        _ <- IO.delay {
          (clock.realTime _).expects(TimeUnit.MILLISECONDS).returning(IO.pure(timestamp + (60 seconds).toMillis))
        }
        tokenExpiredException <- authenticationService.authenticate(Secret(uuid.toString)).error

        _ = {
          tokenExpiredException.getMessage mustBe s"Authentication token expired at ${new DateTime(timestamp + (40 seconds).toMillis)}"
        }

        missingTokenException <- authenticationService.authenticate(Secret(uuid.toString)).error

        _ = {
          missingTokenException.getMessage mustBe "Authentication cookie/token not found"
        }
      }
      yield (): Unit
  }

}
