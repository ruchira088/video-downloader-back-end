package com.ruchij.api.web.routes

import cats.effect.IO
import com.ruchij.api.services.authentication.AuthenticationService.Secret
import com.ruchij.api.services.authentication.models.AuthenticationToken
import com.ruchij.api.test.data.ApiTestData
import com.ruchij.api.test.matchers._
import com.ruchij.api.test.mixins.io.MockedRoutesIO
import com.ruchij.api.web.middleware.Authenticator
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.types.TimeUtils
import org.http4s.dsl.io._
import org.http4s.implicits.http4sLiteralsSyntax
import org.http4s.{Method, Request, RequestCookie, Response, Status, Uri}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class AuthenticatedRoutingSpec extends AnyFlatSpec with Matchers with MockedRoutesIO {

  private val testTimestamp = TimeUtils.instantOf(2022, 8, 1, 10, 10)
  private val testSecret = Secret("test-secret-uuid")
  private val normalUserToken =
    AuthenticationToken(
      ApiTestData.NormalUser.id,
      testSecret,
      testTimestamp.plusSeconds(3600),
      testTimestamp,
      0,
      testTimestamp
    )

  private def run(method: Method, uri: Uri, authenticated: Boolean): IO[Response[IO]] = {
    val request = Request[IO](method = method, uri = uri)

    IO.delay {
      if (authenticated)
        (authenticationService.authenticate _)
          .expects(testSecret)
          .returns(IO.pure((normalUserToken, ApiTestData.NormalUser)))
    } *>
      ignoreHttpMetrics() *>
      createRoutes().run {
        if (authenticated) request.addCookie(RequestCookie(Authenticator.CookieName, testSecret.value)) else request
      }
  }

  private def mustKeepSessionCookie(response: Response[IO]): Unit =
    response.cookies.filter(_.name == Authenticator.CookieName).foreach { cookie =>
      cookie.content must not be empty
    }

  "An authenticated request that matches no route" should "return 404 and keep the session cookie" in runIO {
    run(GET, uri"/schedule/does-not-exist", authenticated = true).flatMap { response =>
      IO.delay {
        response must haveStatus(Status.NotFound)
        mustKeepSessionCookie(response)
      }
    }
  }

  it should "not be signed out when the method does not match an existing path" in runIO {
    run(PATCH, uri"/videos/search", authenticated = true).flatMap { response =>
      IO.delay {
        response.status must not be Status.Unauthorized
        response must haveStatus(Status.NotFound)
        mustKeepSessionCookie(response)
      }
    }
  }

  it should "return 404 under a prefix that mixes unauthenticated and authenticated routes" in runIO {
    run(GET, uri"/users/does-not-exist", authenticated = true).flatMap { response =>
      IO.delay {
        response must haveStatus(Status.NotFound)
        mustKeepSessionCookie(response)
      }
    }
  }

  "An unauthenticated request under an authenticated prefix" should "return 401 for an existing route" in runIO {
    run(GET, uri"/schedule/search", authenticated = false).flatMap { response =>
      IO.delay(response must haveStatus(Status.Unauthorized))
    }
  }

  it should "return 401 for a path that matches no route" in runIO {
    run(GET, uri"/schedule/does-not-exist", authenticated = false).flatMap { response =>
      IO.delay(response must haveStatus(Status.Unauthorized))
    }
  }
}
