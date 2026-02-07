package com.ruchij.api.services.user

import cats.effect.IO
import cats.~>
import com.ruchij.api.daos.credentials.CredentialsDao
import com.ruchij.api.daos.credentials.models.Credentials
import com.ruchij.api.daos.credentials.models.Credentials.HashedPassword
import com.ruchij.api.daos.resettoken.CredentialsResetTokenDao
import com.ruchij.api.daos.resettoken.models.CredentialsResetToken
import com.ruchij.api.daos.user.UserDao
import com.ruchij.api.daos.user.models.{Email, Role, User}
import com.ruchij.api.exceptions.{AuthorizationException, ResourceConflictException}
import com.ruchij.api.services.authentication.AuthenticationService.Password
import com.ruchij.api.services.hashing.PasswordHashingService
import com.ruchij.core.daos.permission.VideoPermissionDao
import com.ruchij.core.daos.title.VideoTitleDao
import com.ruchij.core.exceptions.ResourceNotFoundException
import com.ruchij.core.test.IOSupport.{IOWrapper, runIO}
import com.ruchij.core.test.Providers
import com.ruchij.core.types.{Clock, RandomGenerator, TimeUtils}
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.util.UUID

class UserServiceImplSpec extends AnyFlatSpec with Matchers with MockFactory {

  private val timestamp = TimeUtils.instantOf(2024, 5, 15, 10, 30)
  private val userId = "user-123"
  private val adminId = "admin-456"
  private val testEmail = Email("test@example.com")
  private val testPassword = Password("secure-password")
  private val testHashedPassword = HashedPassword("hashed-password")
  private val resetToken = "reset-token-abc"

  private val sampleUser = User(
    userId,
    timestamp,
    "John",
    "Doe",
    testEmail,
    Role.User
  )

  private val adminUser = User(
    adminId,
    timestamp,
    "Admin",
    "User",
    Email("admin@example.com"),
    Role.Admin
  )

  private def createService(
    passwordHashingService: PasswordHashingService[IO],
    userDao: UserDao[IO],
    credentialsDao: CredentialsDao[IO],
    credentialsResetTokenDao: CredentialsResetTokenDao[IO],
    videoTitleDao: VideoTitleDao[IO],
    videoPermissionDao: VideoPermissionDao[IO]
  )(implicit clock: Clock[IO], uuidGenerator: RandomGenerator[IO, UUID]): UserServiceImpl[IO, IO] = {
    implicit val transaction: IO ~> IO = new (IO ~> IO) {
      override def apply[A](fa: IO[A]): IO[A] = fa
    }

    new UserServiceImpl[IO, IO](
      passwordHashingService,
      userDao,
      credentialsDao,
      credentialsResetTokenDao,
      videoTitleDao,
      videoPermissionDao
    )
  }

  "create" should "successfully create a new user" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    implicit val uuidGenerator: RandomGenerator[IO, UUID] = mock[RandomGenerator[IO, UUID]]

    val passwordHashingService = mock[PasswordHashingService[IO]]
    val userDao = mock[UserDao[IO]]
    val credentialsDao = mock[CredentialsDao[IO]]
    val credentialsResetTokenDao = mock[CredentialsResetTokenDao[IO]]
    val videoTitleDao = mock[VideoTitleDao[IO]]
    val videoPermissionDao = mock[VideoPermissionDao[IO]]

    (userDao.findByEmail _).expects(testEmail).returning(IO.pure(None))
    (passwordHashingService.hashPassword _).expects(testPassword).returning(IO.pure(testHashedPassword))
    (() => uuidGenerator.generate).expects().returning(IO.pure(UUID.fromString("00000000-0000-0000-0000-000000000123")))
    (userDao.insert _).expects(*).returning(IO.pure(1))
    (credentialsDao.insert _).expects(*).returning(IO.pure(1))

    val userService = createService(
      passwordHashingService, userDao, credentialsDao,
      credentialsResetTokenDao, videoTitleDao, videoPermissionDao
    )

    userService.create("John", "Doe", testEmail, testPassword).map { user =>
      user.firstName mustBe "John"
      user.lastName mustBe "Doe"
      user.email mustBe testEmail
      user.role mustBe Role.User
    }
  }

  it should "throw ResourceConflictException when email already exists" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    implicit val uuidGenerator: RandomGenerator[IO, UUID] = mock[RandomGenerator[IO, UUID]]

    val passwordHashingService = mock[PasswordHashingService[IO]]
    val userDao = mock[UserDao[IO]]
    val credentialsDao = mock[CredentialsDao[IO]]
    val credentialsResetTokenDao = mock[CredentialsResetTokenDao[IO]]
    val videoTitleDao = mock[VideoTitleDao[IO]]
    val videoPermissionDao = mock[VideoPermissionDao[IO]]

    (userDao.findByEmail _).expects(testEmail).returning(IO.pure(Some(sampleUser)))

    val userService = createService(
      passwordHashingService, userDao, credentialsDao,
      credentialsResetTokenDao, videoTitleDao, videoPermissionDao
    )

    userService.create("John", "Doe", testEmail, testPassword).error.map { error =>
      error mustBe a[ResourceConflictException]
      error.getMessage mustBe s"User with email ${testEmail.value} already exists"
    }
  }

  "getById" should "return user when found" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    implicit val uuidGenerator: RandomGenerator[IO, UUID] = mock[RandomGenerator[IO, UUID]]

    val passwordHashingService = mock[PasswordHashingService[IO]]
    val userDao = mock[UserDao[IO]]
    val credentialsDao = mock[CredentialsDao[IO]]
    val credentialsResetTokenDao = mock[CredentialsResetTokenDao[IO]]
    val videoTitleDao = mock[VideoTitleDao[IO]]
    val videoPermissionDao = mock[VideoPermissionDao[IO]]

    (userDao.findById _).expects(userId).returning(IO.pure(Some(sampleUser)))

    val userService = createService(
      passwordHashingService, userDao, credentialsDao,
      credentialsResetTokenDao, videoTitleDao, videoPermissionDao
    )

    userService.getById(userId).map { user =>
      user mustBe sampleUser
    }
  }

  it should "throw ResourceNotFoundException when user not found" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    implicit val uuidGenerator: RandomGenerator[IO, UUID] = mock[RandomGenerator[IO, UUID]]

    val passwordHashingService = mock[PasswordHashingService[IO]]
    val userDao = mock[UserDao[IO]]
    val credentialsDao = mock[CredentialsDao[IO]]
    val credentialsResetTokenDao = mock[CredentialsResetTokenDao[IO]]
    val videoTitleDao = mock[VideoTitleDao[IO]]
    val videoPermissionDao = mock[VideoPermissionDao[IO]]

    (userDao.findById _).expects("non-existent").returning(IO.pure(None))

    val userService = createService(
      passwordHashingService, userDao, credentialsDao,
      credentialsResetTokenDao, videoTitleDao, videoPermissionDao
    )

    userService.getById("non-existent").error.map { error =>
      error mustBe a[ResourceNotFoundException]
      error.getMessage mustBe "User id=non-existent does NOT exist"
    }
  }

  "getByEmail" should "return user when found" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    implicit val uuidGenerator: RandomGenerator[IO, UUID] = mock[RandomGenerator[IO, UUID]]

    val passwordHashingService = mock[PasswordHashingService[IO]]
    val userDao = mock[UserDao[IO]]
    val credentialsDao = mock[CredentialsDao[IO]]
    val credentialsResetTokenDao = mock[CredentialsResetTokenDao[IO]]
    val videoTitleDao = mock[VideoTitleDao[IO]]
    val videoPermissionDao = mock[VideoPermissionDao[IO]]

    (userDao.findByEmail _).expects(testEmail).returning(IO.pure(Some(sampleUser)))

    val userService = createService(
      passwordHashingService, userDao, credentialsDao,
      credentialsResetTokenDao, videoTitleDao, videoPermissionDao
    )

    userService.getByEmail(testEmail).map { user =>
      user mustBe sampleUser
    }
  }

  it should "throw ResourceNotFoundException when user not found by email" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    implicit val uuidGenerator: RandomGenerator[IO, UUID] = mock[RandomGenerator[IO, UUID]]

    val passwordHashingService = mock[PasswordHashingService[IO]]
    val userDao = mock[UserDao[IO]]
    val credentialsDao = mock[CredentialsDao[IO]]
    val credentialsResetTokenDao = mock[CredentialsResetTokenDao[IO]]
    val videoTitleDao = mock[VideoTitleDao[IO]]
    val videoPermissionDao = mock[VideoPermissionDao[IO]]

    val nonExistentEmail = Email("nonexistent@example.com")
    (userDao.findByEmail _).expects(nonExistentEmail).returning(IO.pure(None))

    val userService = createService(
      passwordHashingService, userDao, credentialsDao,
      credentialsResetTokenDao, videoTitleDao, videoPermissionDao
    )

    userService.getByEmail(nonExistentEmail).error.map { error =>
      error mustBe a[ResourceNotFoundException]
      error.getMessage mustBe s"User not found email=${nonExistentEmail.value}"
    }
  }

  "forgotPassword" should "create a password reset token" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    implicit val uuidGenerator: RandomGenerator[IO, UUID] = mock[RandomGenerator[IO, UUID]]

    val passwordHashingService = mock[PasswordHashingService[IO]]
    val userDao = mock[UserDao[IO]]
    val credentialsDao = mock[CredentialsDao[IO]]
    val credentialsResetTokenDao = mock[CredentialsResetTokenDao[IO]]
    val videoTitleDao = mock[VideoTitleDao[IO]]
    val videoPermissionDao = mock[VideoPermissionDao[IO]]

    (() => uuidGenerator.generate).expects().returning(IO.pure(UUID.fromString("00000000-0000-0000-0000-000000000abc")))
    (userDao.findByEmail _).expects(testEmail).returning(IO.pure(Some(sampleUser)))
    (credentialsResetTokenDao.insert _).expects(*).returning(IO.pure(1))

    val userService = createService(
      passwordHashingService, userDao, credentialsDao,
      credentialsResetTokenDao, videoTitleDao, videoPermissionDao
    )

    userService.forgotPassword(testEmail).map { resetToken =>
      resetToken.userId mustBe userId
      resetToken.createdAt mustBe timestamp
    }
  }

  it should "throw ResourceNotFoundException when email not found" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    implicit val uuidGenerator: RandomGenerator[IO, UUID] = mock[RandomGenerator[IO, UUID]]

    val passwordHashingService = mock[PasswordHashingService[IO]]
    val userDao = mock[UserDao[IO]]
    val credentialsDao = mock[CredentialsDao[IO]]
    val credentialsResetTokenDao = mock[CredentialsResetTokenDao[IO]]
    val videoTitleDao = mock[VideoTitleDao[IO]]
    val videoPermissionDao = mock[VideoPermissionDao[IO]]

    val nonExistentEmail = Email("nonexistent@example.com")
    (() => uuidGenerator.generate).expects().returning(IO.pure(UUID.fromString("00000000-0000-0000-0000-000000000abc")))
    (userDao.findByEmail _).expects(nonExistentEmail).returning(IO.pure(None))

    val userService = createService(
      passwordHashingService, userDao, credentialsDao,
      credentialsResetTokenDao, videoTitleDao, videoPermissionDao
    )

    userService.forgotPassword(nonExistentEmail).error.map { error =>
      error mustBe a[ResourceNotFoundException]
      error.getMessage mustBe s"User not found email=${nonExistentEmail.value}"
    }
  }

  "resetPassword" should "successfully reset password with valid token" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    implicit val uuidGenerator: RandomGenerator[IO, UUID] = mock[RandomGenerator[IO, UUID]]

    val passwordHashingService = mock[PasswordHashingService[IO]]
    val userDao = mock[UserDao[IO]]
    val credentialsDao = mock[CredentialsDao[IO]]
    val credentialsResetTokenDao = mock[CredentialsResetTokenDao[IO]]
    val videoTitleDao = mock[VideoTitleDao[IO]]
    val videoPermissionDao = mock[VideoPermissionDao[IO]]

    val newPassword = Password("new-secure-password")
    val newHashedPassword = HashedPassword("new-hashed-password")
    val validResetToken = CredentialsResetToken(userId, timestamp.minus(java.time.Duration.ofHours(1)), resetToken)

    (passwordHashingService.hashPassword _).expects(newPassword).returning(IO.pure(newHashedPassword))
    (credentialsResetTokenDao.find _).expects(userId, resetToken).returning(IO.pure(Some(validResetToken)))
    (credentialsDao.update _).expects(*).returning(IO.pure(1))
    (credentialsResetTokenDao.delete _).expects(userId, resetToken).returning(IO.pure(1))
    (userDao.findById _).expects(userId).returning(IO.pure(Some(sampleUser)))

    val userService = createService(
      passwordHashingService, userDao, credentialsDao,
      credentialsResetTokenDao, videoTitleDao, videoPermissionDao
    )

    userService.resetPassword(userId, resetToken, newPassword).map { user =>
      user mustBe sampleUser
    }
  }

  it should "throw ResourceNotFoundException when reset token not found" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    implicit val uuidGenerator: RandomGenerator[IO, UUID] = mock[RandomGenerator[IO, UUID]]

    val passwordHashingService = mock[PasswordHashingService[IO]]
    val userDao = mock[UserDao[IO]]
    val credentialsDao = mock[CredentialsDao[IO]]
    val credentialsResetTokenDao = mock[CredentialsResetTokenDao[IO]]
    val videoTitleDao = mock[VideoTitleDao[IO]]
    val videoPermissionDao = mock[VideoPermissionDao[IO]]

    val newPassword = Password("new-secure-password")
    val newHashedPassword = HashedPassword("new-hashed-password")

    (passwordHashingService.hashPassword _).expects(newPassword).returning(IO.pure(newHashedPassword))
    (credentialsResetTokenDao.find _).expects(userId, "invalid-token").returning(IO.pure(None))
    // productR causes findById to be called at "build time" even though it won't execute
    (userDao.findById _).expects(userId).returning(IO.pure(None))

    val userService = createService(
      passwordHashingService, userDao, credentialsDao,
      credentialsResetTokenDao, videoTitleDao, videoPermissionDao
    )

    userService.resetPassword(userId, "invalid-token", newPassword).error.map { error =>
      error mustBe a[ResourceNotFoundException]
      error.getMessage mustBe "Reset password token in not valid"
    }
  }

  it should "throw ResourceNotFoundException when reset token is expired" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    implicit val uuidGenerator: RandomGenerator[IO, UUID] = mock[RandomGenerator[IO, UUID]]

    val passwordHashingService = mock[PasswordHashingService[IO]]
    val userDao = mock[UserDao[IO]]
    val credentialsDao = mock[CredentialsDao[IO]]
    val credentialsResetTokenDao = mock[CredentialsResetTokenDao[IO]]
    val videoTitleDao = mock[VideoTitleDao[IO]]
    val videoPermissionDao = mock[VideoPermissionDao[IO]]

    val newPassword = Password("new-secure-password")
    val newHashedPassword = HashedPassword("new-hashed-password")
    val expiredResetToken = CredentialsResetToken(userId, timestamp.minus(java.time.Duration.ofHours(5)), resetToken)

    (passwordHashingService.hashPassword _).expects(newPassword).returning(IO.pure(newHashedPassword))
    (credentialsResetTokenDao.find _).expects(userId, resetToken).returning(IO.pure(Some(expiredResetToken)))
    // productR causes findById to be called at "build time" even though it won't execute
    (userDao.findById _).expects(userId).returning(IO.pure(None))

    val userService = createService(
      passwordHashingService, userDao, credentialsDao,
      credentialsResetTokenDao, videoTitleDao, videoPermissionDao
    )

    userService.resetPassword(userId, resetToken, newPassword).error.map { error =>
      error mustBe a[ResourceNotFoundException]
      error.getMessage mustBe "Reset password token in not valid"
    }
  }

  "delete" should "successfully delete user when called by admin" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    implicit val uuidGenerator: RandomGenerator[IO, UUID] = mock[RandomGenerator[IO, UUID]]

    val passwordHashingService = mock[PasswordHashingService[IO]]
    val userDao = mock[UserDao[IO]]
    val credentialsDao = mock[CredentialsDao[IO]]
    val credentialsResetTokenDao = mock[CredentialsResetTokenDao[IO]]
    val videoTitleDao = mock[VideoTitleDao[IO]]
    val videoPermissionDao = mock[VideoPermissionDao[IO]]

    (videoTitleDao.delete _).expects(None, Some(userId)).returning(IO.pure(2))
    (videoPermissionDao.delete _).expects(Some(userId), None).returning(IO.pure(3))
    (credentialsDao.deleteByUserId _).expects(userId).returning(IO.pure(1))
    (userDao.findById _).expects(userId).returning(IO.pure(Some(sampleUser)))
    (userDao.deleteById _).expects(userId).returning(IO.pure(1))

    val userService = createService(
      passwordHashingService, userDao, credentialsDao,
      credentialsResetTokenDao, videoTitleDao, videoPermissionDao
    )

    userService.delete(userId, adminUser).map { user =>
      user mustBe sampleUser
    }
  }

  it should "throw AuthorizationException when called by non-admin user" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    implicit val uuidGenerator: RandomGenerator[IO, UUID] = mock[RandomGenerator[IO, UUID]]

    val passwordHashingService = mock[PasswordHashingService[IO]]
    val userDao = mock[UserDao[IO]]
    val credentialsDao = mock[CredentialsDao[IO]]
    val credentialsResetTokenDao = mock[CredentialsResetTokenDao[IO]]
    val videoTitleDao = mock[VideoTitleDao[IO]]
    val videoPermissionDao = mock[VideoPermissionDao[IO]]

    val nonAdminUser = sampleUser.copy(id = "other-user", role = Role.User)

    val userService = createService(
      passwordHashingService, userDao, credentialsDao,
      credentialsResetTokenDao, videoTitleDao, videoPermissionDao
    )

    userService.delete(userId, nonAdminUser).error.map { error =>
      error mustBe a[AuthorizationException]
      error.getMessage mustBe s"User does NOT have permission to delete user: $userId"
    }
  }

  it should "throw ResourceNotFoundException when user to delete not found" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    implicit val uuidGenerator: RandomGenerator[IO, UUID] = mock[RandomGenerator[IO, UUID]]

    val passwordHashingService = mock[PasswordHashingService[IO]]
    val userDao = mock[UserDao[IO]]
    val credentialsDao = mock[CredentialsDao[IO]]
    val credentialsResetTokenDao = mock[CredentialsResetTokenDao[IO]]
    val videoTitleDao = mock[VideoTitleDao[IO]]
    val videoPermissionDao = mock[VideoPermissionDao[IO]]

    (videoTitleDao.delete _).expects(None, Some("non-existent")).returning(IO.pure(0))
    (videoPermissionDao.delete _).expects(Some("non-existent"), None).returning(IO.pure(0))
    (credentialsDao.deleteByUserId _).expects("non-existent").returning(IO.pure(0))
    (userDao.findById _).expects("non-existent").returning(IO.pure(None))
    // productL causes deleteById to be called at "build time" even though it won't execute
    (userDao.deleteById _).expects("non-existent").returning(IO.pure(0))

    val userService = createService(
      passwordHashingService, userDao, credentialsDao,
      credentialsResetTokenDao, videoTitleDao, videoPermissionDao
    )

    userService.delete("non-existent", adminUser).error.map { error =>
      error mustBe a[ResourceNotFoundException]
      error.getMessage mustBe "User id=non-existent does NOT exist"
    }
  }

  "create" should "generate proper credentials with timestamp" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    implicit val uuidGenerator: RandomGenerator[IO, UUID] = mock[RandomGenerator[IO, UUID]]

    val passwordHashingService = mock[PasswordHashingService[IO]]
    val userDao = mock[UserDao[IO]]
    val credentialsDao = mock[CredentialsDao[IO]]
    val credentialsResetTokenDao = mock[CredentialsResetTokenDao[IO]]
    val videoTitleDao = mock[VideoTitleDao[IO]]
    val videoPermissionDao = mock[VideoPermissionDao[IO]]

    val generatedUuid = UUID.fromString("11111111-1111-1111-1111-111111111111")
    val email = Email("new.user@example.com")

    (userDao.findByEmail _).expects(email).returning(IO.pure(None))
    (passwordHashingService.hashPassword _).expects(testPassword).returning(IO.pure(testHashedPassword))
    (() => uuidGenerator.generate).expects().returning(IO.pure(generatedUuid))
    (userDao.insert _).expects(where { user: User =>
      user.id == generatedUuid.toString &&
        user.firstName == "Jane" &&
        user.lastName == "Smith" &&
        user.email == email &&
        user.role == Role.User &&
        user.createdAt == timestamp
    }).returning(IO.pure(1))
    (credentialsDao.insert _).expects(where { creds: Credentials =>
      creds.userId == generatedUuid.toString &&
        creds.hashedPassword == testHashedPassword &&
        creds.lastUpdatedAt == timestamp
    }).returning(IO.pure(1))

    val userService = createService(
      passwordHashingService, userDao, credentialsDao,
      credentialsResetTokenDao, videoTitleDao, videoPermissionDao
    )

    userService.create("Jane", "Smith", email, testPassword).map { user =>
      user.id mustBe generatedUuid.toString
      user.createdAt mustBe timestamp
      user.firstName mustBe "Jane"
      user.lastName mustBe "Smith"
      user.email mustBe email
      user.role mustBe Role.User
    }
  }

  "forgotPassword" should "generate proper reset token with correct timestamp" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    implicit val uuidGenerator: RandomGenerator[IO, UUID] = mock[RandomGenerator[IO, UUID]]

    val passwordHashingService = mock[PasswordHashingService[IO]]
    val userDao = mock[UserDao[IO]]
    val credentialsDao = mock[CredentialsDao[IO]]
    val credentialsResetTokenDao = mock[CredentialsResetTokenDao[IO]]
    val videoTitleDao = mock[VideoTitleDao[IO]]
    val videoPermissionDao = mock[VideoPermissionDao[IO]]

    val tokenUuid = UUID.fromString("22222222-2222-2222-2222-222222222222")

    (() => uuidGenerator.generate).expects().returning(IO.pure(tokenUuid))
    (userDao.findByEmail _).expects(testEmail).returning(IO.pure(Some(sampleUser)))
    (credentialsResetTokenDao.insert _).expects(where { token: CredentialsResetToken =>
      token.userId == userId &&
        token.token == tokenUuid.toString &&
        token.createdAt == timestamp
    }).returning(IO.pure(1))

    val userService = createService(
      passwordHashingService, userDao, credentialsDao,
      credentialsResetTokenDao, videoTitleDao, videoPermissionDao
    )

    userService.forgotPassword(testEmail).map { resetToken =>
      resetToken.userId mustBe userId
      resetToken.token mustBe tokenUuid.toString
      resetToken.createdAt mustBe timestamp
    }
  }

  "delete" should "clean up all user associations before deletion" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    implicit val uuidGenerator: RandomGenerator[IO, UUID] = mock[RandomGenerator[IO, UUID]]

    val passwordHashingService = mock[PasswordHashingService[IO]]
    val userDao = mock[UserDao[IO]]
    val credentialsDao = mock[CredentialsDao[IO]]
    val credentialsResetTokenDao = mock[CredentialsResetTokenDao[IO]]
    val videoTitleDao = mock[VideoTitleDao[IO]]
    val videoPermissionDao = mock[VideoPermissionDao[IO]]

    val targetUserId = "target-user-to-delete"
    val targetUser = User(
      targetUserId,
      timestamp,
      "Target",
      "User",
      Email("target@example.com"),
      Role.User
    )

    (videoTitleDao.delete _).expects(None, Some(targetUserId)).returning(IO.pure(5))
    (videoPermissionDao.delete _).expects(Some(targetUserId), None).returning(IO.pure(10))
    (credentialsDao.deleteByUserId _).expects(targetUserId).returning(IO.pure(1))
    (userDao.findById _).expects(targetUserId).returning(IO.pure(Some(targetUser)))
    (userDao.deleteById _).expects(targetUserId).returning(IO.pure(1))

    val userService = createService(
      passwordHashingService, userDao, credentialsDao,
      credentialsResetTokenDao, videoTitleDao, videoPermissionDao
    )

    userService.delete(targetUserId, adminUser).map { user =>
      user mustBe targetUser
    }
  }

  "resetPassword" should "update credentials with new password and timestamp" in runIO {
    val currentTimestamp = TimeUtils.instantOf(2024, 5, 15, 14, 30)
    implicit val clock: Clock[IO] = Providers.stubClock[IO](currentTimestamp)
    implicit val uuidGenerator: RandomGenerator[IO, UUID] = mock[RandomGenerator[IO, UUID]]

    val passwordHashingService = mock[PasswordHashingService[IO]]
    val userDao = mock[UserDao[IO]]
    val credentialsDao = mock[CredentialsDao[IO]]
    val credentialsResetTokenDao = mock[CredentialsResetTokenDao[IO]]
    val videoTitleDao = mock[VideoTitleDao[IO]]
    val videoPermissionDao = mock[VideoPermissionDao[IO]]

    val newPassword = Password("brand-new-password")
    val newHashedPassword = HashedPassword("new-hashed-value")
    val validResetToken = CredentialsResetToken(userId, currentTimestamp.minus(java.time.Duration.ofHours(2)), resetToken)

    (passwordHashingService.hashPassword _).expects(newPassword).returning(IO.pure(newHashedPassword))
    (credentialsResetTokenDao.find _).expects(userId, resetToken).returning(IO.pure(Some(validResetToken)))
    (credentialsDao.update _).expects(where { creds: Credentials =>
      creds.userId == userId &&
        creds.hashedPassword == newHashedPassword &&
        creds.lastUpdatedAt == currentTimestamp
    }).returning(IO.pure(1))
    (credentialsResetTokenDao.delete _).expects(userId, resetToken).returning(IO.pure(1))
    (userDao.findById _).expects(userId).returning(IO.pure(Some(sampleUser)))

    val userService = createService(
      passwordHashingService, userDao, credentialsDao,
      credentialsResetTokenDao, videoTitleDao, videoPermissionDao
    )

    userService.resetPassword(userId, resetToken, newPassword).map { user =>
      user mustBe sampleUser
    }
  }

  "resetPassword" should "reject token that is exactly at expiry boundary" in runIO {
    val currentTimestamp = TimeUtils.instantOf(2024, 5, 15, 14, 30)
    implicit val clock: Clock[IO] = Providers.stubClock[IO](currentTimestamp)
    implicit val uuidGenerator: RandomGenerator[IO, UUID] = mock[RandomGenerator[IO, UUID]]

    val passwordHashingService = mock[PasswordHashingService[IO]]
    val userDao = mock[UserDao[IO]]
    val credentialsDao = mock[CredentialsDao[IO]]
    val credentialsResetTokenDao = mock[CredentialsResetTokenDao[IO]]
    val videoTitleDao = mock[VideoTitleDao[IO]]
    val videoPermissionDao = mock[VideoPermissionDao[IO]]

    val newPassword = Password("new-password")
    val newHashedPassword = HashedPassword("hashed")
    val exactlyExpiredToken = CredentialsResetToken(userId, currentTimestamp.minus(java.time.Duration.ofHours(4)), resetToken)

    (passwordHashingService.hashPassword _).expects(newPassword).returning(IO.pure(newHashedPassword))
    (credentialsResetTokenDao.find _).expects(userId, resetToken).returning(IO.pure(Some(exactlyExpiredToken)))
    // productR causes findById to be called at "build time" even though it won't execute
    (userDao.findById _).expects(userId).returning(IO.pure(None))

    val userService = createService(
      passwordHashingService, userDao, credentialsDao,
      credentialsResetTokenDao, videoTitleDao, videoPermissionDao
    )

    userService.resetPassword(userId, resetToken, newPassword).error.map { error =>
      error mustBe a[ResourceNotFoundException]
      error.getMessage mustBe "Reset password token in not valid"
    }
  }

  "resetPassword" should "accept token that is just before expiry" in runIO {
    val currentTimestamp = TimeUtils.instantOf(2024, 5, 15, 14, 30)
    implicit val clock: Clock[IO] = Providers.stubClock[IO](currentTimestamp)
    implicit val uuidGenerator: RandomGenerator[IO, UUID] = mock[RandomGenerator[IO, UUID]]

    val passwordHashingService = mock[PasswordHashingService[IO]]
    val userDao = mock[UserDao[IO]]
    val credentialsDao = mock[CredentialsDao[IO]]
    val credentialsResetTokenDao = mock[CredentialsResetTokenDao[IO]]
    val videoTitleDao = mock[VideoTitleDao[IO]]
    val videoPermissionDao = mock[VideoPermissionDao[IO]]

    val newPassword = Password("new-password")
    val newHashedPassword = HashedPassword("hashed")
    val almostExpiredToken = CredentialsResetToken(userId, currentTimestamp.minus(java.time.Duration.ofHours(3)).minus(java.time.Duration.ofMinutes(59)), resetToken)

    (passwordHashingService.hashPassword _).expects(newPassword).returning(IO.pure(newHashedPassword))
    (credentialsResetTokenDao.find _).expects(userId, resetToken).returning(IO.pure(Some(almostExpiredToken)))
    (credentialsDao.update _).expects(*).returning(IO.pure(1))
    (credentialsResetTokenDao.delete _).expects(userId, resetToken).returning(IO.pure(1))
    (userDao.findById _).expects(userId).returning(IO.pure(Some(sampleUser)))

    val userService = createService(
      passwordHashingService, userDao, credentialsDao,
      credentialsResetTokenDao, videoTitleDao, videoPermissionDao
    )

    userService.resetPassword(userId, resetToken, newPassword).map { user =>
      user mustBe sampleUser
    }
  }
}
