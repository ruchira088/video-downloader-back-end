package com.ruchij.api.services.authentication

import cats.effect.IO
import cats.effect.kernel.Async
import cats.implicits._
import com.ruchij.api.daos.credentials.DoobieCredentialsDao
import com.ruchij.api.daos.permission.DoobieVideoPermissionDao
import com.ruchij.api.daos.resettoken.DoobieCredentialsResetTokenDao
import com.ruchij.api.daos.title.DoobieVideoTitleDao
import com.ruchij.api.daos.user.DoobieUserDao
import com.ruchij.api.daos.user.models.Email
import com.ruchij.api.external.embedded.EmbeddedExternalApiServiceProvider
import com.ruchij.api.services.authentication.AuthenticationService.{Password, Secret}
import com.ruchij.api.services.authentication.models.AuthenticationToken
import com.ruchij.api.services.authentication.models.AuthenticationToken.{AuthenticationKeySpace, AuthenticationTokenKey}
import com.ruchij.api.services.hashing.BCryptPasswordHashingService
import com.ruchij.api.services.user.{UserService, UserServiceImpl}
import com.ruchij.core.kv.{KeySpacedKeyValueStore, RedisKeyValueStore}
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.types.{JodaClock, RandomGenerator}
import doobie.free.connection.ConnectionIO
import org.joda.time.{DateTime, DateTimeZone}
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

class AuthenticationServiceImplSpec extends AnyFlatSpec with Matchers with MockFactory {

  def runTest[F[_]: Async](
    testCase: (RandomGenerator[F, Secret], JodaClock[F], UserService[F], AuthenticationService[F]) => F[Unit]
  ): F[Unit] = {
    val externalApiServiceProvider = new EmbeddedExternalApiServiceProvider[F]

    externalApiServiceProvider.redisConfiguration
      .flatMap(RedisKeyValueStore.create[F])
      .map { redisKeyValueStore =>
        new KeySpacedKeyValueStore[F, AuthenticationTokenKey, AuthenticationToken](
          AuthenticationKeySpace,
          redisKeyValueStore
        )
      }
      .use { keySpacedKeyValueStore =>
        externalApiServiceProvider.transactor.use { implicit transactor =>
          implicit val jodaClock: JodaClock[F] = mock[JodaClock[F]]
          implicit val randomGenerator: RandomGenerator[F, Secret] = mock[RandomGenerator[F, Secret]]

          val passwordHashingService = new BCryptPasswordHashingService[F]

          val authenticationService =
            new AuthenticationServiceImpl[F, ConnectionIO](
              keySpacedKeyValueStore,
              passwordHashingService,
              DoobieUserDao,
              DoobieCredentialsDao,
              20 seconds
            )

          val userService =
            new UserServiceImpl[F, ConnectionIO](
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
    runIO {
      runTest[IO] {
        (secretGenerator, jodaClock, userService, authenticationService) =>
          val timestamp = new DateTime(2022, 10, 8, 19, 0, DateTimeZone.UTC)

          for {
            _ <- IO.delay { (jodaClock.timestamp _).expects().anyNumberOfTimes().returning(IO.pure(timestamp)) }
            email <- RandomGenerator[IO, UUID].generate.map(uuid => Email(s"$uuid@ruchij.com"))
            password = Password("my-password")
            user <- userService.create("Ruchira", "Jayasekara", email, password)
            secret <- RandomGenerator[IO, UUID].generate.map(uuid => Secret(uuid.toString))

            _ <- IO.delay((secretGenerator.generate _).expects().returning(IO.pure(secret)))

            authenticationTokenOne <- authenticationService.login(email, password)

            _ <- IO.delay {
              authenticationTokenOne.secret mustBe secret
              authenticationTokenOne.issuedAt mustBe timestamp
              authenticationTokenOne.renewals mustBe 0
              authenticationTokenOne.expiresAt mustBe timestamp.plusSeconds(20)
            }

            //        _ <- IO.delay {
            //          (clock.realTime _).expects(TimeUnit.MILLISECONDS).returning(IO.pure(timestamp + (10 seconds).toMillis))
            //        }
            //        authenticationTokenTwo <- authenticationService.authenticate(Secret(uuid.toString))
            //
            //        _ <- IO.delay {
            //          authenticationTokenTwo.secret mustBe Secret(uuid.toString)
            //          authenticationTokenTwo.issuedAt.getMillis mustBe timestamp
            //          authenticationTokenTwo.renewals mustBe 1
            //          authenticationTokenTwo.expiresAt.getMillis mustBe (timestamp + (40 seconds).toMillis)
            //        }
            //
            //        _ <- authenticationService.logout(Secret(uuid.toString))
            //
            //        authenticationException <- authenticationService.authenticate(Secret(uuid.toString)).error
            //
            //        _ <- IO.delay {
            //          authenticationException.getMessage mustBe "Authentication cookie/token not found"
            //        }

          } yield (): Unit
      }
    }

//  it should "return an exception if the password is incorrect" in runTest { (_, _, _, authenticationService) =>
//    authenticationService
//      .login(Password("invalid-password"))
//      .error
//      .flatMap { authenticationException =>
//        IO.delay {
//          authenticationException.getMessage mustBe "Invalid password"
//        }
//      }
//  }
//
//  it should "return an exception if the authentication token is missing" in runTest {
//    (_, _, _, authenticationService) =>
//      authenticationService
//        .authenticate(Secret("missing-secret"))
//        .error
//        .flatMap { authenticationException =>
//          IO.delay {
//            authenticationException.getMessage mustBe "Authentication cookie/token not found"
//          }
//        }
//  }
//
//  it should "return an exception if the authentication token is expired" in runTest {
//    (timestamp, uuid, clock, authenticationService) =>
//      for {
//        _ <- IO.delay {
//          (clock.realTime _).expects(TimeUnit.MILLISECONDS).returning(IO.pure(timestamp))
//        }
//        _ <- authenticationService.login(Password("top-secret"))
//
//        _ <- IO.delay {
//          (clock.realTime _).expects(TimeUnit.MILLISECONDS).returning(IO.pure(timestamp + (10 seconds).toMillis))
//        }
//        _ <- authenticationService.authenticate(Secret(uuid.toString))
//
//        _ <- IO.delay {
//          (clock.realTime _).expects(TimeUnit.MILLISECONDS).returning(IO.pure(timestamp + (60 seconds).toMillis))
//        }
//        tokenExpiredException <- authenticationService.authenticate(Secret(uuid.toString)).error
//
//        _ <- IO.delay {
//          tokenExpiredException.getMessage mustBe s"Authentication token expired at ${new DateTime(timestamp + (40 seconds).toMillis)}"
//        }
//
//        missingTokenException <- authenticationService.authenticate(Secret(uuid.toString)).error
//
//        _ <- IO.delay {
//          missingTokenException.getMessage mustBe "Authentication cookie/token not found"
//        }
//      } yield (): Unit
//  }

}
