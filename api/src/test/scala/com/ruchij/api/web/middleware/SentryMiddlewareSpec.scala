package com.ruchij.api.web.middleware

import cats.data.Kleisli
import cats.effect.IO
import com.ruchij.core.test.IOSupport.runIO
import org.http4s.{ContextRequest, Method, Request, Response, Status, Uri}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class SentryMiddlewareSpec extends AnyFlatSpec with Matchers {

  private def contextRequest(method: Method = Method.GET, uri: Uri = Uri.unsafeFromString("/test")): ContextRequest[IO, String] =
    ContextRequest("test-context", Request[IO](method = method, uri = uri))

  private def successApp: Kleisli[IO, ContextRequest[IO, String], Response[IO]] =
    Kleisli(_ => IO.pure(Response[IO](Status.Ok)))

  private def errorApp(error: Throwable): Kleisli[IO, ContextRequest[IO, String], Response[IO]] =
    Kleisli(_ => IO.raiseError(error))

  private def statusApp(status: Status): Kleisli[IO, ContextRequest[IO, String], Response[IO]] =
    Kleisli(_ => IO.pure(Response[IO](status)))

  "SentryMiddleware.apply" should "pass through successful responses" in runIO {
    val middleware = SentryMiddleware(successApp)

    for {
      response <- middleware.run(contextRequest())
    } yield response.status mustBe Status.Ok
  }

  it should "re-raise exceptions after capturing them" in runIO {
    val error = new RuntimeException("test error")
    val middleware = SentryMiddleware(errorApp(error))

    middleware.run(contextRequest()).attempt.map { result =>
      result.isLeft mustBe true
      result.left.toOption.get mustBe error
    }
  }

  it should "re-raise the original exception type" in runIO {
    val error = new IllegalArgumentException("bad argument")
    val middleware = SentryMiddleware(errorApp(error))

    middleware.run(contextRequest()).attempt.map { result =>
      result.isLeft mustBe true
      result.left.toOption.get mustBe a[IllegalArgumentException]
      result.left.toOption.get.getMessage mustBe "bad argument"
    }
  }

  "SentryMiddleware.captureResponseErrors" should "pass through successful responses without side effects" in runIO {
    val middleware = SentryMiddleware.captureResponseErrors(successApp)

    for {
      response <- middleware.run(contextRequest())
    } yield response.status mustBe Status.Ok
  }

  it should "pass through non-500 error responses" in runIO {
    val middleware = SentryMiddleware.captureResponseErrors(statusApp(Status.BadRequest))

    for {
      response <- middleware.run(contextRequest())
    } yield response.status mustBe Status.BadRequest
  }

  it should "capture 500 Internal Server Error responses" in runIO {
    val middleware = SentryMiddleware.captureResponseErrors(statusApp(Status.InternalServerError))

    for {
      response <- middleware.run(contextRequest())
    } yield response.status mustBe Status.InternalServerError
  }

  it should "capture 502 Bad Gateway responses" in runIO {
    val middleware = SentryMiddleware.captureResponseErrors(statusApp(Status.BadGateway))

    for {
      response <- middleware.run(contextRequest())
    } yield response.status mustBe Status.BadGateway
  }

  it should "capture 503 Service Unavailable responses" in runIO {
    val middleware = SentryMiddleware.captureResponseErrors(statusApp(Status.ServiceUnavailable))

    for {
      response <- middleware.run(contextRequest())
    } yield response.status mustBe Status.ServiceUnavailable
  }

  it should "not capture 404 Not Found responses" in runIO {
    val middleware = SentryMiddleware.captureResponseErrors(statusApp(Status.NotFound))

    for {
      response <- middleware.run(contextRequest())
    } yield response.status mustBe Status.NotFound
  }

  it should "not capture 401 Unauthorized responses" in runIO {
    val middleware = SentryMiddleware.captureResponseErrors(statusApp(Status.Unauthorized))

    for {
      response <- middleware.run(contextRequest())
    } yield response.status mustBe Status.Unauthorized
  }

  it should "work with different HTTP methods" in runIO {
    val middleware = SentryMiddleware.captureResponseErrors(statusApp(Status.InternalServerError))

    for {
      getResponse <- middleware.run(contextRequest(method = Method.GET))
      postResponse <- middleware.run(contextRequest(method = Method.POST))
      deleteResponse <- middleware.run(contextRequest(method = Method.DELETE))
    } yield {
      getResponse.status mustBe Status.InternalServerError
      postResponse.status mustBe Status.InternalServerError
      deleteResponse.status mustBe Status.InternalServerError
    }
  }
}
