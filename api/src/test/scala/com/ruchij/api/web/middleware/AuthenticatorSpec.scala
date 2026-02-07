package com.ruchij.api.web.middleware

import cats.effect.IO
import com.ruchij.api.services.authentication.AuthenticationService.Secret
import com.ruchij.core.test.IOSupport.runIO
import org.http4s._
import org.http4s.headers.Authorization
import org.http4s.implicits._
import com.ruchij.core.types.TimeUtils
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class AuthenticatorSpec extends AnyFlatSpec with Matchers {

  "CookieName" should "be 'authentication'" in {
    Authenticator.CookieName mustBe "authentication"
  }

  "authenticationSecret" should "extract secret from bearer token" in {
    val request = Request[IO](Method.GET, uri"/test")
      .putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, "my-secret-token")))

    val secret = Authenticator.authenticationSecret(request)

    secret mustBe Some(Secret("my-secret-token"))
  }

  it should "extract secret from cookie" in {
    val request = Request[IO](Method.GET, uri"/test")
      .addCookie(RequestCookie("authentication", "cookie-secret"))

    val secret = Authenticator.authenticationSecret(request)

    secret mustBe Some(Secret("cookie-secret"))
  }

  it should "prefer cookie over bearer token" in {
    val request = Request[IO](Method.GET, uri"/test")
      .putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, "bearer-token")))
      .addCookie(RequestCookie("authentication", "cookie-secret"))

    val secret = Authenticator.authenticationSecret(request)

    secret mustBe Some(Secret("cookie-secret"))
  }

  it should "return None when no authentication is present" in {
    val request = Request[IO](Method.GET, uri"/test")

    val secret = Authenticator.authenticationSecret(request)

    secret mustBe None
  }

  it should "ignore empty cookie content" in {
    val request = Request[IO](Method.GET, uri"/test")
      .addCookie(RequestCookie("authentication", ""))

    val secret = Authenticator.authenticationSecret(request)

    secret mustBe None
  }

  it should "ignore whitespace-only cookie content" in {
    val request = Request[IO](Method.GET, uri"/test")
      .addCookie(RequestCookie("authentication", "   "))

    val secret = Authenticator.authenticationSecret(request)

    secret mustBe None
  }

  it should "ignore other authentication schemes" in {
    val request = Request[IO](Method.GET, uri"/test")
      .putHeaders(Authorization(Credentials.Token(AuthScheme.Basic, "basic-credentials")))

    val secret = Authenticator.authenticationSecret(request)

    secret mustBe None
  }

  it should "ignore other cookies" in {
    val request = Request[IO](Method.GET, uri"/test")
      .addCookie(RequestCookie("other-cookie", "some-value"))

    val secret = Authenticator.authenticationSecret(request)

    secret mustBe None
  }

  "addCookie" should "add authentication cookie to response" in runIO {
    import com.ruchij.api.services.authentication.models.AuthenticationToken

    val token = AuthenticationToken(
      userId = "user-123",
      secret = Secret("test-token"),
      expiresAt = TimeUtils.instantOf(2024, 5, 15, 11, 0),
      issuedAt = TimeUtils.instantOf(2024, 5, 15, 10, 0),
      renewals = 0L
    )
    val response = Response[IO](Status.Ok)

    Authenticator.addCookie[IO](token, response).map { updatedResponse =>
      val cookie = updatedResponse.cookies.find(_.name == "authentication")
      cookie.isDefined mustBe true
      cookie.get.content mustBe "test-token"
      cookie.get.path mustBe Some("/")
      cookie.get.secure mustBe true
      cookie.get.sameSite mustBe Some(SameSite.None)
    }
  }

  it should "set expiry date on cookie" in runIO {
    import com.ruchij.api.services.authentication.models.AuthenticationToken

    val expiryTime = TimeUtils.instantOf(2024, 5, 15, 12, 0)
    val token = AuthenticationToken(
      userId = "user-456",
      secret = Secret("test-token"),
      expiresAt = expiryTime,
      issuedAt = TimeUtils.instantOf(2024, 5, 15, 10, 0),
      renewals = 1L
    )
    val response = Response[IO](Status.Ok)

    Authenticator.addCookie[IO](token, response).map { updatedResponse =>
      val cookie = updatedResponse.cookies.find(_.name == "authentication")
      cookie.isDefined mustBe true
      cookie.get.expires.isDefined mustBe true
    }
  }
}
