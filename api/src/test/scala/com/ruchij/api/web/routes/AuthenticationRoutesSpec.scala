package com.ruchij.api.web.routes

import cats.effect.IO
import com.ruchij.api.daos.user.models.Email
import com.ruchij.api.exceptions.AuthenticationException
import com.ruchij.api.services.authentication.AuthenticationService.{Password, Secret}
import com.ruchij.api.services.authentication.models.AuthenticationToken
import com.ruchij.api.test.data.ApiTestData
import com.ruchij.api.test.matchers._
import com.ruchij.api.test.mixins.io.MockedRoutesIO
import com.ruchij.core.test.IOSupport.runIO
import io.circe.literal._
import org.http4s.headers.{Authorization, Cookie}
import org.http4s.{AuthScheme, Credentials, Request, Status}
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.client.dsl.io._
import org.http4s.dsl.io._
import org.http4s.implicits.http4sLiteralsSyntax
import com.ruchij.core.types.TimeUtils
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class AuthenticationRoutesSpec extends AnyFlatSpec with Matchers with MockedRoutesIO {

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

  "POST /authentication/login" should "return a successful response with authentication token when credentials are valid" in runIO {
    val email = Email("john.smith@ruchij.com")
    val password = Password("secure-password")

    val expectedJsonResponse =
      json"""{
        "userId": "john.smith",
        "secret": "test-secret-uuid",
        "expiresAt": "2022-09-15T10:10:00Z",
        "issuedAt": "2022-08-01T10:10:00Z",
        "renewals": 0
      }"""

    (authenticationService.login _)
      .expects(email, password)
      .returns(IO.pure(testToken))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          POST(
            json"""{"email": "john.smith@ruchij.com", "password": "secure-password"}""",
            uri"/authentication/login"
          )
        )
        .flatMap { response =>
          IO.delay {
            response must beJsonContentType
            response must haveJson(expectedJsonResponse)
            response must haveStatus(Status.Created)
            response.cookies.find(_.name == "authentication") mustBe defined
          }
        }
  }

  it should "return an error response when credentials are invalid" in runIO {
    val email = Email("john.smith@ruchij.com")
    val password = Password("wrong-password")

    (authenticationService.login _)
      .expects(email, password)
      .returns(IO.raiseError(AuthenticationException("Invalid credentials")))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          POST(
            json"""{"email": "john.smith@ruchij.com", "password": "wrong-password"}""",
            uri"/authentication/login"
          )
        )
        .flatMap { response =>
          IO.delay {
            response must beJsonContentType
            response must haveStatus(Status.Unauthorized)
          }
        }
  }

  "DELETE /authentication/logout" should "return a successful response when logging out with a valid token" in runIO {
    (authenticationService.logout _)
      .expects(testSecret)
      .returns(IO.pure(ApiTestData.AdminUser))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = DELETE,
            uri = uri"/authentication/logout",
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

  it should "return NoContent when logging out without authentication" in runIO {
    ignoreHttpMetrics() *>
      createRoutes()
        .run(DELETE(uri"/authentication/logout"))
        .flatMap { response =>
          IO.delay {
            response must haveStatus(Status.NoContent)
          }
        }
  }

  "GET /authentication/user" should "return the authenticated user details" in runIO {
    (authenticationService.authenticate _)
      .expects(testSecret)
      .returns(IO.pure((testToken, ApiTestData.AdminUser)))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = GET,
            uri = uri"/authentication/user",
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

  it should "return Unauthorized when no authentication token is provided" in runIO {
    ignoreHttpMetrics() *>
      createRoutes()
        .run(GET(uri"/authentication/user"))
        .flatMap { response =>
          IO.delay {
            response must haveStatus(Status.Unauthorized)
          }
        }
  }

  it should "authenticate using cookie when bearer token is not provided" in runIO {
    (authenticationService.authenticate _)
      .expects(testSecret)
      .returns(IO.pure((testToken, ApiTestData.NormalUser)))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = GET,
            uri = uri"/authentication/user",
            headers = org.http4s.Headers(
              Cookie(org.http4s.RequestCookie("authentication", testSecret.value))
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

  it should "return Unauthorized when authentication token is invalid" in runIO {
    val invalidSecret = Secret("invalid-secret")

    (authenticationService.authenticate _)
      .expects(invalidSecret)
      .returns(IO.raiseError(AuthenticationException("Invalid authentication token")))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = GET,
            uri = uri"/authentication/user",
            headers = org.http4s.Headers(
              Authorization(Credentials.Token(AuthScheme.Bearer, invalidSecret.value))
            )
          )
        )
        .flatMap { response =>
          IO.delay {
            response must haveStatus(Status.Unauthorized)
          }
        }
  }
}
