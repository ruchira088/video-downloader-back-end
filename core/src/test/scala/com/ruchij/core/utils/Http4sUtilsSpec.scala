package com.ruchij.core.utils

import cats.effect.IO
import com.ruchij.core.exceptions.ExternalServiceException
import com.ruchij.core.test.IOSupport.runIO
import org.http4s.headers.{`Content-Length`, `Content-Type`}
import org.http4s.{Headers, MediaType, Response, Status}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class Http4sUtilsSpec extends AnyFlatSpec with Matchers {

  "Http4sUtils.header" should "extract Content-Length header from response" in runIO {
    val response = Response[IO](
      status = Status.Ok,
      headers = Headers(`Content-Length`.unsafeFromLong(1024L))
    )

    Http4sUtils.header[IO, `Content-Length`].run(response).map { contentLength =>
      contentLength.length mustBe 1024L
    }
  }

  it should "extract Content-Type header from response" in runIO {
    val response = Response[IO](
      status = Status.Ok,
      headers = Headers(`Content-Type`(MediaType.video.mp4))
    )

    Http4sUtils.header[IO, `Content-Type`].run(response).map { contentType =>
      contentType.mediaType mustBe MediaType.video.mp4
    }
  }

  it should "raise ExternalServiceException when header is missing" in runIO {
    val response = Response[IO](
      status = Status.Ok,
      headers = Headers.empty
    )

    Http4sUtils.header[IO, `Content-Length`].run(response).attempt.map { result =>
      result.isLeft mustBe true
      result.left.toOption.get mustBe a[ExternalServiceException]
      result.left.toOption.get.getMessage must include("Content-Length")
    }
  }

  it should "include existing headers in error message" in runIO {
    val response = Response[IO](
      status = Status.Ok,
      headers = Headers(`Content-Type`(MediaType.video.mp4))
    )

    Http4sUtils.header[IO, `Content-Length`].run(response).attempt.map { result =>
      result.isLeft mustBe true
      result.left.toOption.get.getMessage must include("Content-Type")
    }
  }

  it should "work with responses containing multiple headers" in runIO {
    val response = Response[IO](
      status = Status.Ok,
      headers = Headers(
        `Content-Length`.unsafeFromLong(2048L),
        `Content-Type`(MediaType.video.webm)
      )
    )

    for {
      contentLength <- Http4sUtils.header[IO, `Content-Length`].run(response)
      contentType <- Http4sUtils.header[IO, `Content-Type`].run(response)
      _ <- IO.delay {
        contentLength.length mustBe 2048L
        contentType.mediaType mustBe MediaType.video.webm
      }
    } yield ()
  }
}
