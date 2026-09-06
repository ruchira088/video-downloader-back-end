package com.ruchij.core.exceptions

import cats.data.NonEmptyList
import org.http4s.implicits.http4sLiteralsSyntax
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class ExceptionsSpec extends AnyFlatSpec with Matchers {

  "UnsupportedVideoUrlException" should "include the URI in its message" in {
    val exception = UnsupportedVideoUrlException(uri"https://example.com/video/123")

    exception.getMessage mustBe "Unsupported video URL: https://example.com/video/123"
  }

  "AggregatedException" should "combine the error messages" in {
    val exception =
      AggregatedException(NonEmptyList.of(new RuntimeException("Error 1"), new RuntimeException("Error 2")))

    exception.getMessage mustBe "Error 1; Error 2"
  }
}
