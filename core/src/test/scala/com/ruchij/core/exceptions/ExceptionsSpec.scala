package com.ruchij.core.exceptions

import cats.data.NonEmptyList
import org.http4s.implicits.http4sLiteralsSyntax
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class ExceptionsSpec extends AnyFlatSpec with Matchers {

  "ResourceNotFoundException" should "extend Exception" in {
    val exception = ResourceNotFoundException("Resource not found")
    exception mustBe an[Exception]
  }

  it should "store the message" in {
    val message = "Video not found"
    val exception = ResourceNotFoundException(message)
    exception.getMessage mustBe message
  }

  it should "be a case class" in {
    val exception1 = ResourceNotFoundException("not found")
    val exception2 = ResourceNotFoundException("not found")
    exception1 mustBe exception2
  }

  "InvalidConditionException" should "extend Exception" in {
    val exception = InvalidConditionException("Invalid condition")
    exception mustBe an[Exception]
  }

  it should "store the message" in {
    val message = "Expected 1 row but got 0"
    val exception = InvalidConditionException(message)
    exception.getMessage mustBe message
  }

  "ValidationException" should "extend Exception" in {
    val exception = ValidationException("Validation failed")
    exception mustBe an[Exception]
  }

  it should "store the message" in {
    val message = "Invalid email format"
    val exception = ValidationException(message)
    exception.getMessage mustBe message
  }

  "UnsupportedVideoUrlException" should "extend Exception" in {
    val exception = UnsupportedVideoUrlException(uri"https://unsupported.com/video")
    exception mustBe an[Exception]
  }

  it should "include URI in message" in {
    val testUri = uri"https://example.com/video/123"
    val exception = UnsupportedVideoUrlException(testUri)
    exception.getMessage must include("example.com")
    exception.getMessage must include("Unsupported video URL")
  }

  it should "store the URI" in {
    val testUri = uri"https://test.com/path"
    val exception = UnsupportedVideoUrlException(testUri)
    exception.uri mustBe testUri
  }

  "ExternalServiceException" should "extend Exception" in {
    val exception = ExternalServiceException("External service failed")
    exception mustBe an[Exception]
  }

  it should "store the message" in {
    val message = "Failed to connect to external API"
    val exception = ExternalServiceException(message)
    exception.getMessage mustBe message
  }

  "CliCommandException" should "extend Exception" in {
    val exception = CliCommandException("ffmpeg error: file not found")
    exception mustBe an[Exception]
  }

  it should "store the error message" in {
    val errorMessage = "Command failed with exit code 1"
    val exception = CliCommandException(errorMessage)
    exception.error mustBe errorMessage
    exception.getMessage mustBe errorMessage
  }

  it should "be a case class" in {
    val exception1 = CliCommandException("error1")
    val exception2 = CliCommandException("error1")
    exception1 mustBe exception2
  }

  "AggregatedException" should "extend Exception" in {
    val errors = NonEmptyList.of(
      new RuntimeException("Error 1"),
      new RuntimeException("Error 2")
    )
    val exception = AggregatedException(errors)
    exception mustBe an[Exception]
  }

  it should "store the list of throwables" in {
    val errors = NonEmptyList.of(
      new RuntimeException("Error 1"),
      new RuntimeException("Error 2"),
      new RuntimeException("Error 3")
    )
    val exception = AggregatedException(errors)
    exception.errors mustBe errors
  }

  it should "handle single error" in {
    val error = new RuntimeException("Single error")
    val exception = AggregatedException(NonEmptyList.one(error))
    exception.errors.head mustBe error
  }
}
