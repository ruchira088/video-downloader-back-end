package com.ruchij.api.web.routes

import cats.effect.IO
import com.ruchij.api.daos.resettoken.models.CredentialsResetToken
import com.ruchij.api.daos.user.models.{Email, Role, User}
import com.ruchij.api.exceptions.{AuthorizationException, ResourceConflictException}
import com.ruchij.api.services.authentication.AuthenticationService.{Password, Secret}
import com.ruchij.api.services.authentication.models.AuthenticationToken
import com.ruchij.api.test.data.ApiTestData
import com.ruchij.api.test.matchers._
import com.ruchij.api.test.mixins.io.MockedRoutesIO
import com.ruchij.core.exceptions.ResourceNotFoundException
import com.ruchij.core.test.IOSupport.runIO
import io.circe.literal._
import org.http4s.headers.Authorization
import org.http4s.{AuthScheme, Credentials, Request, Status}
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.client.dsl.io._
import org.http4s.dsl.io._
import org.http4s.implicits.http4sLiteralsSyntax
import com.ruchij.core.types.TimeUtils
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class UserRoutesSpec extends AnyFlatSpec with Matchers with MockedRoutesIO {

  private val testTimestamp = TimeUtils.instantOf(2022, 8, 1, 10, 10)
  private val expiresAt = testTimestamp.plus(java.time.Duration.ofDays(45))
  private val testSecret = Secret("test-secret-uuid")
  private val testToken = AuthenticationToken(
    ApiTestData.AdminUser.id,
    testSecret,
    expiresAt,
    testTimestamp,
    0
  )

  "POST /user" should "create a new user successfully" in runIO {
    val email = Email("new.user@example.com")
    val password = Password("secure-password")
    val newUser = User("new-user-id", testTimestamp, "New", "User", email, Role.User)

    val expectedJsonResponse =
      json"""{
        "id": "new-user-id",
        "createdAt": "2022-08-01T10:10:00Z",
        "firstName": "New",
        "lastName": "User",
        "email": "new.user@example.com",
        "role": "User"
      }"""

    (userService.create _)
      .expects("New", "User", email, password)
      .returns(IO.pure(newUser))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          POST(
            json"""{"firstName": "New", "lastName": "User", "email": "new.user@example.com", "password": "secure-password"}""",
            uri"/users"
          )
        )
        .flatMap { response =>
          IO.delay {
            response must beJsonContentType
            response must haveJson(expectedJsonResponse)
            response must haveStatus(Status.Created)
          }
        }
  }

  it should "return a conflict error when email already exists" in runIO {
    val email = Email("existing@example.com")
    val password = Password("password")

    (userService.create _)
      .expects("John", "Doe", email, password)
      .returns(IO.raiseError(ResourceConflictException(s"User already exists with email: ${email.value}")))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          POST(
            json"""{"firstName": "John", "lastName": "Doe", "email": "existing@example.com", "password": "password"}""",
            uri"/users"
          )
        )
        .flatMap { response =>
          IO.delay {
            response must beJsonContentType
            response must haveStatus(Status.Conflict)
          }
        }
  }

  "PUT /user/forgot-password" should "return success when email exists" in runIO {
    val email = Email("john.smith@ruchij.com")
    val resetToken = CredentialsResetToken("john.smith", testTimestamp, "reset-token-123")

    val expectedJsonResponse =
      json"""{
        "result": "Password reset token sent to john.smith@ruchij.com"
      }"""

    (userService.forgotPassword _)
      .expects(email)
      .returns(IO.pure(resetToken))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          PUT(
            json"""{"email": "john.smith@ruchij.com"}""",
            uri"/users/forgot-password"
          )
        )
        .flatMap { response =>
          IO.delay {
            response must beJsonContentType
            response must haveJson(expectedJsonResponse)
            response must haveStatus(Status.Ok)
          }
        }
  }

  it should "return not found when email does not exist" in runIO {
    val email = Email("nonexistent@example.com")

    (userService.forgotPassword _)
      .expects(email)
      .returns(IO.raiseError(ResourceNotFoundException(s"User not found with email: ${email.value}")))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          PUT(
            json"""{"email": "nonexistent@example.com"}""",
            uri"/users/forgot-password"
          )
        )
        .flatMap { response =>
          IO.delay {
            response must haveStatus(Status.NotFound)
          }
        }
  }

  "PUT /user/id/:userId/reset-password" should "reset password successfully" in runIO {
    val password = Password("new-secure-password")
    val updatedUser = ApiTestData.NormalUser

    (userService.resetPassword _)
      .expects("alice.doe", "valid-reset-token", password)
      .returns(IO.pure(updatedUser))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          PUT(
            json"""{"token": "valid-reset-token", "password": "new-secure-password"}""",
            uri"/users/id/alice.doe/reset-password"
          )
        )
        .flatMap { response =>
          IO.delay {
            response must beJsonContentType
            response must haveStatus(Status.Ok)
          }
        }
  }

  it should "return not found when reset token is invalid" in runIO {
    val password = Password("new-password")

    (userService.resetPassword _)
      .expects("alice.doe", "invalid-token", password)
      .returns(IO.raiseError(ResourceNotFoundException("Invalid reset token")))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          PUT(
            json"""{"token": "invalid-token", "password": "new-password"}""",
            uri"/users/id/alice.doe/reset-password"
          )
        )
        .flatMap { response =>
          IO.delay {
            response must haveStatus(Status.NotFound)
          }
        }
  }

  "DELETE /user/id/:userId" should "delete user successfully when admin is authenticated" in runIO {
    (authenticationService.authenticate _)
      .expects(testSecret)
      .returns(IO.pure((testToken, ApiTestData.AdminUser)))

    (userService.delete _)
      .expects("alice.doe", ApiTestData.AdminUser)
      .returns(IO.pure(ApiTestData.NormalUser))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = DELETE,
            uri = uri"/users/id/alice.doe",
            headers = org.http4s.Headers(
              Authorization(Credentials.Token(AuthScheme.Bearer, testSecret.value))
            )
          )
        )
        .flatMap { response =>
          IO.delay {
            response must beJsonContentType
            response must haveStatus(Status.Ok)
          }
        }
  }

  it should "return unauthorized when no authentication is provided" in runIO {
    ignoreHttpMetrics() *>
      createRoutes()
        .run(DELETE(uri"/users/id/alice.doe"))
        .flatMap { response =>
          IO.delay {
            response must haveStatus(Status.Unauthorized)
          }
        }
  }

  it should "return forbidden when non-admin user tries to delete another user" in runIO {
    val normalUserToken = AuthenticationToken(
      ApiTestData.NormalUser.id,
      testSecret,
      expiresAt,
      testTimestamp,
      0
    )

    (authenticationService.authenticate _)
      .expects(testSecret)
      .returns(IO.pure((normalUserToken, ApiTestData.NormalUser)))

    (userService.delete _)
      .expects("john.smith", ApiTestData.NormalUser)
      .returns(IO.raiseError(AuthorizationException("Only administrators can delete users")))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = DELETE,
            uri = uri"/users/id/john.smith",
            headers = org.http4s.Headers(
              Authorization(Credentials.Token(AuthScheme.Bearer, testSecret.value))
            )
          )
        )
        .flatMap { response =>
          IO.delay {
            response must haveStatus(Status.Forbidden)
          }
        }
  }

  it should "return not found when user to delete does not exist" in runIO {
    (authenticationService.authenticate _)
      .expects(testSecret)
      .returns(IO.pure((testToken, ApiTestData.AdminUser)))

    (userService.delete _)
      .expects("nonexistent-user", ApiTestData.AdminUser)
      .returns(IO.raiseError(ResourceNotFoundException("User not found: nonexistent-user")))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = DELETE,
            uri = uri"/users/id/nonexistent-user",
            headers = org.http4s.Headers(
              Authorization(Credentials.Token(AuthScheme.Bearer, testSecret.value))
            )
          )
        )
        .flatMap { response =>
          IO.delay {
            response must haveStatus(Status.NotFound)
          }
        }
  }
}
