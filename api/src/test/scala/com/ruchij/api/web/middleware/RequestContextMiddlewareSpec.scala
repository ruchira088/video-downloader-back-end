package com.ruchij.api.web.middleware

import cats.data.Kleisli
import cats.effect.IO
import com.ruchij.api.services.models.Context.RequestContext
import com.ruchij.api.web.headers.`X-Request-ID`
import com.ruchij.core.test.IOSupport.runIO
import org.http4s._
import org.http4s.implicits._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class RequestContextMiddlewareSpec extends AnyFlatSpec with Matchers {

  private def testContextHttpApp: Kleisli[IO, ContextRequest[IO, RequestContext], Response[IO]] =
    Kleisli { contextRequest =>
      IO.pure(Response[IO](Status.Ok).withHeaders(
        `X-Request-ID`(contextRequest.context.requestId)
      ))
    }

  "RequestContextMiddleware" should "generate a request ID when none is provided" in runIO {
    val httpApp = RequestContextMiddleware[IO](testContextHttpApp)
    val request = Request[IO](Method.GET, uri"/test")

    httpApp.run(request).map { response =>
      response.status mustBe Status.Ok
      val requestIdHeader = response.headers.get[`X-Request-ID`]
      requestIdHeader.isDefined mustBe true
      // Verify it looks like a UUID
      requestIdHeader.get.value.split("-").length mustBe 5
    }
  }

  it should "use provided X-Request-ID header value" in runIO {
    val customRequestId = "custom-request-id-12345"
    val httpApp = RequestContextMiddleware[IO](testContextHttpApp)
    val request = Request[IO](Method.GET, uri"/test")
      .putHeaders(`X-Request-ID`(customRequestId))

    httpApp.run(request).map { response =>
      response.status mustBe Status.Ok
      val requestIdHeader = response.headers.get[`X-Request-ID`]
      requestIdHeader.isDefined mustBe true
      requestIdHeader.get.value mustBe customRequestId
    }
  }

  it should "pass request context to underlying handler" in runIO {
    var capturedRequestId: Option[String] = None

    val capturingHttpApp: Kleisli[IO, ContextRequest[IO, RequestContext], Response[IO]] =
      Kleisli { contextRequest =>
        capturedRequestId = Some(contextRequest.context.requestId)
        IO.pure(Response[IO](Status.Ok))
      }

    val httpApp = RequestContextMiddleware[IO](capturingHttpApp)
    val request = Request[IO](Method.GET, uri"/test")
      .putHeaders(`X-Request-ID`("test-id-999"))

    httpApp.run(request).map { _ =>
      capturedRequestId mustBe Some("test-id-999")
    }
  }

  it should "add X-Request-ID header to response" in runIO {
    val responseWithoutHeader: Kleisli[IO, ContextRequest[IO, RequestContext], Response[IO]] =
      Kleisli { _ =>
        IO.pure(Response[IO](Status.Ok))
      }

    val httpApp = RequestContextMiddleware[IO](responseWithoutHeader)
    val request = Request[IO](Method.GET, uri"/test")

    httpApp.run(request).map { response =>
      response.headers.get[`X-Request-ID`].isDefined mustBe true
    }
  }

  it should "preserve existing response headers" in runIO {
    val responseWithCustomHeader: Kleisli[IO, ContextRequest[IO, RequestContext], Response[IO]] =
      Kleisli { _ =>
        IO.pure(Response[IO](Status.Ok).putHeaders(
          org.http4s.headers.`Content-Type`(MediaType.application.json)
        ))
      }

    val httpApp = RequestContextMiddleware[IO](responseWithCustomHeader)
    val request = Request[IO](Method.GET, uri"/test")
      .putHeaders(`X-Request-ID`("preserve-test"))

    httpApp.run(request).map { response =>
      response.headers.get[org.http4s.headers.`Content-Type`].isDefined mustBe true
      response.headers.get[`X-Request-ID`].map(_.value) mustBe Some("preserve-test")
    }
  }

  it should "generate unique request IDs for each request when none provided" in runIO {
    val httpApp = RequestContextMiddleware[IO](testContextHttpApp)

    for {
      response1 <- httpApp.run(Request[IO](Method.GET, uri"/test1"))
      response2 <- httpApp.run(Request[IO](Method.GET, uri"/test2"))
      requestId1 = response1.headers.get[`X-Request-ID`].map(_.value)
      requestId2 = response2.headers.get[`X-Request-ID`].map(_.value)
      _ <- IO.delay {
        requestId1.isDefined mustBe true
        requestId2.isDefined mustBe true
        requestId1 must not equal requestId2
      }
    } yield ()
  }
}
