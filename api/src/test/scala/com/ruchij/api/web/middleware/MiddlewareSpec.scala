package com.ruchij.api.web.middleware

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.ruchij.api.exceptions.AuthorizationException
import com.ruchij.core.exceptions.ResourceNotFoundException
import com.ruchij.core.test.IOSupport.runIO
import org.http4s.dsl.io._
import org.http4s.implicits.http4sLiteralsSyntax
import org.http4s.{ContextRequest, ContextRoutes, Request, Status}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class MiddlewareSpec extends AnyFlatSpec with Matchers {

  "NotFoundHandler" should "pass through existing routes" in runIO {
    val routes = ContextRoutes.of[Unit, IO] {
      case GET -> Root / "test" as _ => Ok("found")
    }

    val handler = NotFoundHandler(routes)

    handler
      .run(ContextRequest((), Request[IO](method = GET, uri = uri"/test")))
      .map { response =>
        response.status mustBe Status.Ok
      }
  }

  it should "raise ResourceNotFoundException for non-existing routes" in {
    val routes = ContextRoutes.of[Unit, IO] {
      case GET -> Root / "test" as _ => Ok("found")
    }

    val handler = NotFoundHandler(routes)

    val result = handler
      .run(ContextRequest((), Request[IO](method = GET, uri = uri"/nonexistent")))
      .attempt
      .unsafeRunSync()

    result.isLeft mustBe true
    result.left.toOption.get mustBe a[ResourceNotFoundException]
    result.left.toOption.get.getMessage must include("Endpoint not found")
    result.left.toOption.get.getMessage must include("/nonexistent")
  }

  it should "include HTTP method in error message" in {
    val routes = ContextRoutes.of[Unit, IO] {
      case GET -> Root / "test" as _ => Ok("found")
    }

    val handler = NotFoundHandler(routes)

    val result = handler
      .run(ContextRequest((), Request[IO](method = POST, uri = uri"/something")))
      .attempt
      .unsafeRunSync()

    result.isLeft mustBe true
    result.left.toOption.get.getMessage must include("POST")
  }

  "Authorizer" should "execute block when user has permission" in runIO {
    Authorizer[IO](hasPermission = true) {
      Ok("authorized")
    }.map { response =>
      response.status mustBe Status.Ok
    }
  }

  it should "raise AuthorizationException when user does not have permission" in {
    val result = Authorizer[IO](hasPermission = false) {
      Ok("authorized")
    }.attempt.unsafeRunSync()

    result.isLeft mustBe true
    result.left.toOption.get mustBe a[AuthorizationException]
    result.left.toOption.get.getMessage must include("not authorized")
  }

  it should "use custom error message when provided" in {
    val customMessage = "Custom authorization failure message"

    val result = Authorizer[IO](hasPermission = false, errorMessage = customMessage) {
      Ok("authorized")
    }.attempt.unsafeRunSync()

    result.isLeft mustBe true
    result.left.toOption.get.getMessage mustBe customMessage
  }

  it should "not execute block when permission is denied" in runIO {
    var blockExecuted = false

    val result = Authorizer[IO](hasPermission = false) {
      blockExecuted = true
      Ok("authorized")
    }.attempt

    result.map { _ =>
      blockExecuted mustBe false
    }
  }
}
