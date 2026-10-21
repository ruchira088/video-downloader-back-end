package com.ruchij.api.services.authentication

import cats.effect.{IO, Ref}
import com.ruchij.api.daos.credentials.DoobieCredentialsDao
import com.ruchij.api.daos.credentials.models.Credentials.HashedPassword
import com.ruchij.api.daos.playlist.DoobiePlaylistDao
import com.ruchij.api.daos.resettoken.DoobieCredentialsResetTokenDao
import com.ruchij.api.daos.user.DoobieUserDao
import com.ruchij.api.daos.user.models.{Email, Role, User}
import com.ruchij.api.external.ApiResourcesProvider
import com.ruchij.api.external.containers.ContainerApiResourcesProvider
import com.ruchij.api.services.authentication.AuthenticationService.{Password, Secret}
import com.ruchij.api.services.authentication.models.AuthenticationToken
import com.ruchij.api.services.authentication.models.AuthenticationToken.{AuthenticationKeySpace, AuthenticationTokenKey}
import com.ruchij.api.services.fallback.FallbackSyncRequester
import com.ruchij.api.services.hashing.{BCryptPasswordHashingService, PasswordHashingService}
import com.ruchij.api.services.user.{UserService, UserServiceImpl}
import com.ruchij.api.test.matchers.haveInstant
import com.ruchij.core.daos.permission.DoobieVideoPermissionDao
import com.ruchij.core.daos.resource.DoobieFileResourceDao
import com.ruchij.core.daos.title.DoobieVideoTitleDao
import com.ruchij.core.daos.video.DoobieVideoDao
import com.ruchij.core.kv.{KeySpacedKeyValueStore, KeyValueStore, RedisKeyValueStore}
import com.ruchij.core.kv.codecs.KVEncoder
import com.ruchij.core.kv.keys.KeySpacedKeyEncoder
import com.ruchij.core.services.repository.InMemoryRepositoryService
import com.ruchij.core.test.IOSupport.{IOWrapper, runIO}
import com.ruchij.core.types.{Clock, RandomGenerator, TimeUtils}
import doobie.free.connection.ConnectionIO
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

class AuthenticationServiceImplSpec extends AnyFlatSpec with Matchers with MockFactory {
  import AuthenticationServiceImplSpec._

  private val RevokedTokenMessage = "Authentication token has been revoked"
  private val MissingTokenMessage = "Authentication cookie/token not found"

  private def clockReturns(clock: Clock[IO], instant: Instant): IO[Unit] =
    IO.delay { (() => clock.timestamp).expects().returns(IO.pure(instant)) }.void

  private def createUser(clock: Clock[IO], userService: UserService[IO], password: Password, at: Instant): IO[User] =
    for {
      _ <- clockReturns(clock, at)
      email <- RandomGenerator[IO, UUID].generate.map(uuid => Email(s"$uuid@ruchij.com"))
      user <- userService.create("Ruchira", "Jayasekara", email, password)
    } yield user

  private def login(
    secretGenerator: RandomGenerator[IO, Secret],
    clock: Clock[IO],
    authenticationService: AuthenticationService[IO],
    user: User,
    password: Password,
    at: Instant
  ): IO[Secret] =
    for {
      secret <- RandomGenerator[IO, UUID].generate.map(uuid => Secret(uuid.toString))
      _ <- IO.delay { (() => secretGenerator.generate).expects().returning(IO.pure(secret)) }
      _ <- clockReturns(clock, at)
      _ <- authenticationService.login(user.email, password)
    } yield secret

  private def resetPassword(
    clock: Clock[IO],
    userService: UserService[IO],
    user: User,
    newPassword: Password,
    at: Instant
  ): IO[User] =
    for {
      _ <- clockReturns(clock, at)
      resetToken <- userService.forgotPassword(user.email)
      _ <- clockReturns(clock, at)
      updatedUser <- userService.resetPassword(user.id, resetToken.token, newPassword)
    } yield updatedUser

  def runTest(
    testCase: (RandomGenerator[IO, Secret], Clock[IO], UserService[IO], AuthenticationService[IO]) => IO[Unit]
  ): Unit =
    runTestWithFixtures { (secretGenerator, clock, userService, authenticationService, _) =>
      testCase(secretGenerator, clock, userService, authenticationService)
    }

  def runTestWithFixtures(
    testCase: (
      RandomGenerator[IO, Secret],
      Clock[IO],
      UserService[IO],
      AuthenticationService[IO],
      Fixtures
    ) => IO[Unit]
  ): Unit =
    runIO {
      val apiResourcesProvider: ApiResourcesProvider[IO] = new ContainerApiResourcesProvider[IO]

      apiResourcesProvider.redisConfiguration
        .flatMap(redisConfig => RedisKeyValueStore.create[IO](redisConfig))
        .use { redisKeyValueStore =>
          val keySpacedKeyValueStore =
            new KeySpacedKeyValueStore[IO, AuthenticationTokenKey, AuthenticationToken](
              AuthenticationKeySpace,
              redisKeyValueStore
            )

          apiResourcesProvider.transactor.use { implicit transactor =>
            Ref.of[IO, IO[Unit]](IO.unit).flatMap { passwordCheckHook =>
              implicit val clock: Clock[IO] = mock[Clock[IO]]
              implicit val randomGenerator: RandomGenerator[IO, Secret] = mock[RandomGenerator[IO, Secret]]

              val bCryptPasswordHashingService = new BCryptPasswordHashingService[IO]

              val passwordHashingService = new PasswordHashingService[IO] {
                override def checkPassword(password: Password, hashedPassword: HashedPassword): IO[Boolean] =
                  passwordCheckHook.getAndSet(IO.unit).flatten
                    .productR(bCryptPasswordHashingService.checkPassword(password, hashedPassword))

                override def hashPassword(password: Password): IO[HashedPassword] =
                  bCryptPasswordHashingService.hashPassword(password)
              }

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
                  DoobieVideoPermissionDao,
                  new DoobiePlaylistDao(DoobieFileResourceDao, DoobieVideoDao),
                  DoobieFileResourceDao,
                  new InMemoryRepositoryService[IO](new ConcurrentHashMap[String, List[Byte]]()),
                  FallbackSyncRequester.noOp[IO]
                )

              testCase(
                randomGenerator,
                clock,
                userService,
                authenticationService,
                Fixtures(passwordCheckHook, redisKeyValueStore)
              )
            }
          }
        }
    }

  "Authentication service" should "go through authentication flow" in
    runTest { (secretGenerator, clock, userService, authenticationService) =>
      val timestamp = TimeUtils.instantOf(2022, 10, 8, 19, 0)

      for {
        _ <- IO.delay { (() => clock.timestamp).expects().returns(IO.pure(timestamp)) }

        email <- RandomGenerator[IO, UUID].generate.map(uuid => Email(s"$uuid@ruchij.com"))
        password = Password("my-password")
        user <- userService.create("Ruchira", "Jayasekara", email, password)
        secret <- RandomGenerator[IO, UUID].generate.map(uuid => Secret(uuid.toString))

        _ <- IO.delay { (() => secretGenerator.generate).expects().returning(IO.pure(secret)) }
        _ <- IO.delay { (() => clock.timestamp).expects().returns(IO.pure(timestamp)) }

        authenticationTokenOne <- authenticationService.login(email, password)

        _ <- IO.delay {
          authenticationTokenOne.secret mustBe secret
          authenticationTokenOne.issuedAt must haveInstant(timestamp)
          authenticationTokenOne.renewals mustBe 0
          authenticationTokenOne.credentialsLastUpdatedAt must haveInstant(timestamp)
          authenticationTokenOne.expiresAt must haveInstant(timestamp.plus(java.time.Duration.ofSeconds(20)))
        }

        _ <- IO.delay {
          (() => clock.timestamp).expects().returns(IO.pure(timestamp.plus(java.time.Duration.ofSeconds(10)))).anyNumberOfTimes()
        }

        (authenticationTokenTwo, authUser) <- authenticationService.authenticate(secret)

        _ <- IO.delay {
          authUser mustBe user
          authenticationTokenTwo.secret mustBe secret
          authenticationTokenTwo.issuedAt must haveInstant(timestamp)
          authenticationTokenTwo.renewals mustBe 1
          authenticationTokenTwo.credentialsLastUpdatedAt must haveInstant(timestamp)
          authenticationTokenTwo.expiresAt must haveInstant(timestamp.plus(java.time.Duration.ofSeconds(30)))
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
    (secretGenerator, clock, userService, authenticationService) =>
      val timestamp = TimeUtils.instantOf(2022, 10, 9, 10, 0)

      for {
        _ <- IO.delay { (() => clock.timestamp).expects().returns(IO.pure(timestamp)) }

        email <- RandomGenerator[IO, UUID].generate.map(uuid => Email(s"$uuid@ruchij.com"))
        password = Password("my-password")
        _ <- userService.create("Ruchira", "Jayasekara", email, password)
        secret <- RandomGenerator[IO, UUID].generate.map(uuid => Secret(uuid.toString))

        _ <- IO.delay((() => secretGenerator.generate).expects().returning(IO.pure(secret)))
        _ <- IO.delay { (() => clock.timestamp).expects().returns(IO.pure(timestamp)) }

        _ <- authenticationService.login(email, password)

        _ <- IO.delay { (() => clock.timestamp).expects().returns(IO.pure(timestamp.plus(java.time.Duration.ofSeconds(15)))) }
        _ <- authenticationService.authenticate(secret)

        _ <- IO.delay { (() => clock.timestamp).expects().returns(IO.pure(timestamp.plus(java.time.Duration.ofSeconds(60)))) }
        tokenExpiredException <- authenticationService.authenticate(secret).error

        _ <- IO.delay {
          tokenExpiredException.getMessage mustBe s"Authentication token expired at ${timestamp.plus(java.time.Duration.ofSeconds(35))}"
        }

        missingTokenException <- authenticationService.authenticate(secret).error

        _ <- IO.delay { missingTokenException.getMessage mustBe "Authentication cookie/token not found" }
      } yield (): Unit
  }

  it should "return error when logging out with expired token" in runTest {
    (secretGenerator, clock, userService, authenticationService) =>
      val timestamp = TimeUtils.instantOf(2022, 10, 10, 10, 0)

      for {
        _ <- IO.delay { (() => clock.timestamp).expects().returns(IO.pure(timestamp)) }

        email <- RandomGenerator[IO, UUID].generate.map(uuid => Email(s"$uuid@ruchij.com"))
        password = Password("my-password")
        _ <- userService.create("Ruchira", "Jayasekara", email, password)
        secret <- RandomGenerator[IO, UUID].generate.map(uuid => Secret(uuid.toString))

        _ <- IO.delay((() => secretGenerator.generate).expects().returning(IO.pure(secret)))
        _ <- IO.delay { (() => clock.timestamp).expects().returns(IO.pure(timestamp)) }

        _ <- authenticationService.login(email, password)

        // Logout with expired token - timestamp is way past expiration
        _ <- IO.delay { (() => clock.timestamp).expects().returns(IO.pure(timestamp.plus(java.time.Duration.ofMinutes(30)))) }
        logoutException <- authenticationService.logout(secret).error

        _ <- IO.delay {
          logoutException.getMessage mustBe "Authentication cookie/token not found"
        }
      } yield (): Unit
  }

  it should "successfully logout with valid token" in runTest {
    (secretGenerator, clock, userService, authenticationService) =>
      val timestamp = TimeUtils.instantOf(2022, 10, 11, 10, 0)

      for {
        _ <- IO.delay { (() => clock.timestamp).expects().returns(IO.pure(timestamp)) }

        email <- RandomGenerator[IO, UUID].generate.map(uuid => Email(s"$uuid@ruchij.com"))
        password = Password("my-password")
        user <- userService.create("Ruchira", "Jayasekara", email, password)
        secret <- RandomGenerator[IO, UUID].generate.map(uuid => Secret(uuid.toString))

        _ <- IO.delay((() => secretGenerator.generate).expects().returning(IO.pure(secret)))
        _ <- IO.delay { (() => clock.timestamp).expects().returns(IO.pure(timestamp)) }

        _ <- authenticationService.login(email, password)

        // Logout with valid (non-expired) token
        _ <- IO.delay { (() => clock.timestamp).expects().returns(IO.pure(timestamp.plus(java.time.Duration.ofSeconds(5)))) }
        loggedOutUser <- authenticationService.logout(secret)

        _ <- IO.delay {
          loggedOutUser mustBe user
        }

        // Verify the token is no longer valid
        missingTokenException <- authenticationService.authenticate(secret).error
        _ <- IO.delay {
          missingTokenException.getMessage mustBe "Authentication cookie/token not found"
        }
      } yield (): Unit
  }

  it should "revoke every existing session of the user when the password is reset" in runTest {
    (secretGenerator, clock, userService, authenticationService) =>
      val timestamp = TimeUtils.instantOf(2022, 10, 12, 10, 0)
      val oldPassword = Password("old-password")

      for {
        user <- createUser(clock, userService, oldPassword, timestamp)

        laptopSecret <-
          login(secretGenerator, clock, authenticationService, user, oldPassword, timestamp.plusSeconds(1))
        phoneSecret <-
          login(secretGenerator, clock, authenticationService, user, oldPassword, timestamp.plusSeconds(2))

        _ <- resetPassword(clock, userService, user, Password("new-password"), timestamp.plusSeconds(3))

        _ <- clockReturns(clock, timestamp.plusSeconds(4))
        laptopException <- authenticationService.authenticate(laptopSecret).error
        _ <- clockReturns(clock, timestamp.plusSeconds(4))
        phoneException <- authenticationService.authenticate(phoneSecret).error

        _ <- IO.delay {
          laptopException.getMessage mustBe RevokedTokenMessage
          phoneException.getMessage mustBe RevokedTokenMessage
        }

        // Revoked tokens are removed from the store, so later requests no longer find them
        laptopMissingException <- authenticationService.authenticate(laptopSecret).error
        phoneMissingException <- authenticationService.authenticate(phoneSecret).error

        _ <- IO.delay {
          laptopMissingException.getMessage mustBe MissingTokenMessage
          phoneMissingException.getMessage mustBe MissingTokenMessage
        }
      } yield (): Unit
  }

  it should "revoke a session that was renewed before the password is reset" in runTest {
    (secretGenerator, clock, userService, authenticationService) =>
      val timestamp = TimeUtils.instantOf(2022, 10, 13, 10, 0)
      val password = Password("old-password")

      for {
        user <- createUser(clock, userService, password, timestamp)
        secret <- login(secretGenerator, clock, authenticationService, user, password, timestamp.plusSeconds(1))

        // Renewing the session extends its expiry but must not exempt it from revocation
        _ <- clockReturns(clock, timestamp.plusSeconds(10))
        (renewedToken, _) <- authenticationService.authenticate(secret)
        _ <- IO.delay { renewedToken.renewals mustBe 1 }

        _ <- resetPassword(clock, userService, user, Password("new-password"), timestamp.plusSeconds(11))

        _ <- clockReturns(clock, timestamp.plusSeconds(12))
        exception <- authenticationService.authenticate(secret).error

        _ <- IO.delay { exception.getMessage mustBe RevokedTokenMessage }
      } yield (): Unit
  }

  it should "keep sessions created with the new password valid after the password is reset" in runTest {
    (secretGenerator, clock, userService, authenticationService) =>
      val timestamp = TimeUtils.instantOf(2022, 10, 14, 10, 0)
      val oldPassword = Password("old-password")
      val newPassword = Password("new-password")
      val resetAt = timestamp.plusSeconds(2)

      for {
        user <- createUser(clock, userService, oldPassword, timestamp)
        _ <- login(secretGenerator, clock, authenticationService, user, oldPassword, timestamp.plusSeconds(1))

        _ <- resetPassword(clock, userService, user, newPassword, resetAt)

        oldPasswordException <- authenticationService.login(user.email, oldPassword).error
        _ <- IO.delay { oldPasswordException.getMessage mustBe "Invalid password" }

        // Sessions are bound to the credentials they were issued for, not to when they were issued, so even one
        // issued in the same millisecond as the reset stays valid
        sameInstantSecret <- login(secretGenerator, clock, authenticationService, user, newPassword, resetAt)
        laterSecret <- login(secretGenerator, clock, authenticationService, user, newPassword, resetAt.plusSeconds(1))

        _ <- clockReturns(clock, resetAt.plusSeconds(2))
        (sameInstantToken, sameInstantUser) <- authenticationService.authenticate(sameInstantSecret)
        _ <- clockReturns(clock, resetAt.plusSeconds(2))
        (laterToken, laterUser) <- authenticationService.authenticate(laterSecret)

        _ <- IO.delay {
          sameInstantUser mustBe user
          sameInstantToken.issuedAt must haveInstant(resetAt)
          sameInstantToken.renewals mustBe 1

          laterUser mustBe user
          laterToken.issuedAt must haveInstant(resetAt.plusSeconds(1))
          laterToken.renewals mustBe 1
        }
      } yield (): Unit
  }

  it should "not revoke sessions when a password reset is requested but not completed" in runTest {
    (secretGenerator, clock, userService, authenticationService) =>
      val timestamp = TimeUtils.instantOf(2022, 10, 15, 10, 0)
      val password = Password("my-password")

      for {
        user <- createUser(clock, userService, password, timestamp)
        secret <- login(secretGenerator, clock, authenticationService, user, password, timestamp.plusSeconds(1))

        _ <- clockReturns(clock, timestamp.plusSeconds(2))
        _ <- userService.forgotPassword(user.email)

        _ <- clockReturns(clock, timestamp.plusSeconds(3))
        (authenticationToken, authenticatedUser) <- authenticationService.authenticate(secret)

        _ <- IO.delay {
          authenticatedUser mustBe user
          authenticationToken.secret mustBe secret
        }
      } yield (): Unit
  }

  it should "not revoke sessions when the password reset fails because of an invalid reset token" in runTest {
    (secretGenerator, clock, userService, authenticationService) =>
      val timestamp = TimeUtils.instantOf(2022, 10, 16, 10, 0)
      val password = Password("my-password")

      for {
        user <- createUser(clock, userService, password, timestamp)
        secret <- login(secretGenerator, clock, authenticationService, user, password, timestamp.plusSeconds(1))

        _ <- clockReturns(clock, timestamp.plusSeconds(2))
        _ <- userService.forgotPassword(user.email)

        _ <- clockReturns(clock, timestamp.plusSeconds(3))
        resetException <- userService.resetPassword(user.id, "invalid-reset-token", Password("new-password")).error
        _ <- IO.delay { resetException.getMessage mustBe "Reset password token in not valid" }

        _ <- clockReturns(clock, timestamp.plusSeconds(4))
        (authenticationToken, authenticatedUser) <- authenticationService.authenticate(secret)

        _ <- IO.delay {
          authenticatedUser mustBe user
          authenticationToken.secret mustBe secret
        }
      } yield (): Unit
  }

  it should "not revoke sessions of other users when a user resets their password" in runTest {
    (secretGenerator, clock, userService, authenticationService) =>
      val timestamp = TimeUtils.instantOf(2022, 10, 17, 10, 0)
      val password = Password("my-password")

      for {
        alice <- createUser(clock, userService, password, timestamp)
        bob <- createUser(clock, userService, password, timestamp)

        aliceSecret <- login(secretGenerator, clock, authenticationService, alice, password, timestamp.plusSeconds(1))
        bobSecret <- login(secretGenerator, clock, authenticationService, bob, password, timestamp.plusSeconds(1))

        _ <- resetPassword(clock, userService, alice, Password("new-password"), timestamp.plusSeconds(2))

        _ <- clockReturns(clock, timestamp.plusSeconds(3))
        aliceException <- authenticationService.authenticate(aliceSecret).error
        _ <- clockReturns(clock, timestamp.plusSeconds(3))
        (_, authenticatedBob) <- authenticationService.authenticate(bobSecret)

        _ <- IO.delay {
          aliceException.getMessage mustBe RevokedTokenMessage
          authenticatedBob mustBe bob
        }
      } yield (): Unit
  }

  it should "revoke sessions of a user whose credentials no longer exist" in runTest {
    (secretGenerator, clock, userService, authenticationService) =>
      val timestamp = TimeUtils.instantOf(2022, 10, 18, 10, 0)
      val password = Password("my-password")
      val admin = User("admin-id", timestamp, "Admin", "User", Email("admin@ruchij.com"), Role.Admin)

      for {
        user <- createUser(clock, userService, password, timestamp)
        secret <- login(secretGenerator, clock, authenticationService, user, password, timestamp.plusSeconds(1))

        _ <- userService.delete(user.id, admin)

        _ <- clockReturns(clock, timestamp.plusSeconds(2))
        exception <- authenticationService.authenticate(secret).error
        missingException <- authenticationService.authenticate(secret).error

        _ <- IO.delay {
          exception.getMessage mustBe RevokedTokenMessage
          missingException.getMessage mustBe MissingTokenMessage
        }
      } yield (): Unit
  }

  it should "allow logging out of a session after it was revoked by a password reset" in runTest {
    (secretGenerator, clock, userService, authenticationService) =>
      val timestamp = TimeUtils.instantOf(2022, 10, 19, 10, 0)
      val password = Password("my-password")

      for {
        user <- createUser(clock, userService, password, timestamp)
        secret <- login(secretGenerator, clock, authenticationService, user, password, timestamp.plusSeconds(1))

        _ <- resetPassword(clock, userService, user, Password("new-password"), timestamp.plusSeconds(2))

        _ <- clockReturns(clock, timestamp.plusSeconds(3))
        loggedOutUser <- authenticationService.logout(secret)
        missingException <- authenticationService.authenticate(secret).error

        _ <- IO.delay {
          loggedOutUser mustBe user
          missingException.getMessage mustBe MissingTokenMessage
        }
      } yield (): Unit
  }

  it should "revoke a session whose login verified the old password while the password was being reset" in
    runTestWithFixtures { (secretGenerator, clock, userService, authenticationService, fixtures) =>
      val timestamp = TimeUtils.instantOf(2022, 10, 20, 10, 0)
      val oldPassword = Password("old-password")

      for {
        user <- createUser(clock, userService, oldPassword, timestamp)

        // The reset completes after login has read the old credentials, but before it verifies the password
        // and stamps the token, so the token's issuedAt ends up later than the reset
        _ <- fixtures.passwordCheckHook.set {
          userService.forgotPassword(user.email).flatMap { resetToken =>
            userService.resetPassword(user.id, resetToken.token, Password("new-password")).void
          }
        }
        _ <- clockReturns(clock, timestamp.plusSeconds(2))
        _ <- clockReturns(clock, timestamp.plusSeconds(2))
        secret <- login(secretGenerator, clock, authenticationService, user, oldPassword, timestamp.plusSeconds(3))

        _ <- clockReturns(clock, timestamp.plusSeconds(4))
        exception <- authenticationService.authenticate(secret).error

        _ <- IO.delay { exception.getMessage mustBe RevokedTokenMessage }
      } yield (): Unit
    }

  it should "treat a token issued before tokens were bound to credentials as missing and remove it" in
    runTestWithFixtures { (_, clock, userService, authenticationService, fixtures) =>
      val timestamp = TimeUtils.instantOf(2022, 10, 21, 10, 0)

      // Writes to the same key the authentication service reads, i.e. prefixed with the authentication key space
      implicit val authenticationTokenKeyEncoder: KVEncoder[IO, AuthenticationTokenKey] =
        KeySpacedKeyEncoder[IO, AuthenticationTokenKey]

      for {
        user <- createUser(clock, userService, Password("my-password"), timestamp)
        secret <- RandomGenerator[IO, UUID].generate.map(uuid => Secret(uuid.toString))

        legacyToken = LegacyAuthenticationToken(user.id, secret, timestamp.plusSeconds(20), timestamp, 0)
        _ <- fixtures.keyValueStore.put(AuthenticationTokenKey(secret), legacyToken, None)

        exception <- authenticationService.authenticate(secret).error
        storedValue <- fixtures.keyValueStore.get[AuthenticationTokenKey, LegacyAuthenticationToken](
          AuthenticationTokenKey(secret)
        )

        _ <- IO.delay {
          exception.getMessage mustBe MissingTokenMessage
          storedValue mustBe None
        }
      } yield (): Unit
    }
}

object AuthenticationServiceImplSpec {
  /**
    * @param passwordCheckHook runs once, the next time a password is checked, which lets a test interleave other
    *                          operations between login reading the credentials and verifying the password
    * @param keyValueStore the raw store backing the authentication tokens
    */
  final case class Fixtures(passwordCheckHook: Ref[IO, IO[Unit]], keyValueStore: KeyValueStore[IO])

  // The shape of authentication tokens issued before they were bound to the credentials verified at login
  final case class LegacyAuthenticationToken(
    userId: String,
    secret: Secret,
    expiresAt: Instant,
    issuedAt: Instant,
    renewals: Long
  )
}
