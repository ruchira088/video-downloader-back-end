package com.ruchij.api.services.user

import cats.effect.IO
import cats.effect.kernel.Ref
import com.ruchij.api.daos.resettoken.models.CredentialsResetToken
import com.ruchij.api.daos.user.models.{Email, Role, User}
import com.ruchij.api.services.authentication.AuthenticationService.Password
import com.ruchij.api.services.fallback.FallbackUserRevoker
import com.ruchij.core.exceptions.ResourceNotFoundException
import com.ruchij.core.test.IOSupport.{IOWrapper, runIO}
import com.ruchij.core.types.TimeUtils
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class FallbackRevokingUserServiceSpec extends AnyFlatSpec with Matchers with MockFactory {
  private val timestamp = TimeUtils.instantOf(2024, 5, 15, 10, 30)
  private val user = User("user-1", timestamp, "John", "Doe", Email("me@ruchij.com"), Role.User)
  private val admin = User("admin-1", timestamp, "Admin", "User", Email("admin@ruchij.com"), Role.Admin)

  private class RecordingRevoker(revoked: Ref[IO, List[User]]) extends FallbackUserRevoker[IO] {
    override def revoke(user: User): IO[Unit] = revoked.update(_ :+ user)

    val users: IO[List[User]] = revoked.get
  }

  private def recordingRevoker: IO[RecordingRevoker] = Ref.of[IO, List[User]](Nil).map(new RecordingRevoker(_))

  "FallbackRevokingUserService" should "revoke the user's fallback account after a password reset" in runIO {
    val userService = mock[UserService[IO]]
    (userService.resetPassword _).expects("user-1", "reset-token", Password("new-password")).returns(IO.pure(user))

    for {
      revoker <- recordingRevoker
      result <- new FallbackRevokingUserService[IO](userService, revoker)
        .resetPassword("user-1", "reset-token", Password("new-password"))
      revoked <- revoker.users
    } yield {
      result mustBe user
      revoked mustBe List(user)
    }
  }

  it should "revoke the user's fallback account after the user is deleted" in runIO {
    val userService = mock[UserService[IO]]
    (userService.delete _).expects("user-1", admin).returns(IO.pure(user))

    for {
      revoker <- recordingRevoker
      result <- new FallbackRevokingUserService[IO](userService, revoker).delete("user-1", admin)
      revoked <- revoker.users
    } yield {
      result mustBe user
      revoked mustBe List(user)
    }
  }

  it should "revoke nothing when the password reset fails" in runIO {
    val userService = mock[UserService[IO]]
    val failure = ResourceNotFoundException("Reset password token in not valid")
    (userService.resetPassword _).expects("user-1", "bad-token", Password("new-password")).returns(IO.raiseError(failure))

    for {
      revoker <- recordingRevoker
      error <- new FallbackRevokingUserService[IO](userService, revoker)
        .resetPassword("user-1", "bad-token", Password("new-password"))
        .error
      revoked <- revoker.users
    } yield {
      error mustBe failure
      revoked mustBe empty
    }
  }

  it should "revoke nothing for the other operations" in runIO {
    val userService = mock[UserService[IO]]
    val resetToken = CredentialsResetToken("user-1", timestamp, "reset-token")
    (userService.create _).expects("John", "Doe", Email("me@ruchij.com"), Password("password")).returns(IO.pure(user))
    (userService.getById _).expects("user-1").returns(IO.pure(user))
    (userService.getByEmail _).expects(Email("me@ruchij.com")).returns(IO.pure(user))
    (userService.forgotPassword _).expects(Email("me@ruchij.com")).returns(IO.pure(resetToken))

    for {
      revoker <- recordingRevoker
      service = new FallbackRevokingUserService[IO](userService, revoker)
      created <- service.create("John", "Doe", Email("me@ruchij.com"), Password("password"))
      byId <- service.getById("user-1")
      byEmail <- service.getByEmail(Email("me@ruchij.com"))
      token <- service.forgotPassword(Email("me@ruchij.com"))
      revoked <- revoker.users
    } yield {
      List(created, byId, byEmail) mustBe List(user, user, user)
      token mustBe resetToken
      revoked mustBe empty
    }
  }
}
