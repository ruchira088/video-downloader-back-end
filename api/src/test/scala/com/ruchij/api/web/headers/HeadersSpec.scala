package com.ruchij.api.web.headers

import org.http4s.Header
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class HeadersSpec extends AnyFlatSpec with Matchers {

  "X-Request-ID" should "store a valid request ID value" in {
    val requestId = `X-Request-ID`("req-123")

    requestId.value mustBe "req-123"
  }

  it should "parse valid header value" in {
    val header = implicitly[Header[`X-Request-ID`, Header.Single]]
    val result = header.parse("my-request-id")

    result mustBe Right(`X-Request-ID`("my-request-id"))
  }

  it should "reject empty header value" in {
    val header = implicitly[Header[`X-Request-ID`, Header.Single]]
    val result = header.parse("")

    result.isLeft mustBe true
    result.left.toOption.get.message must include("empty")
  }

  it should "reject whitespace-only header value" in {
    val header = implicitly[Header[`X-Request-ID`, Header.Single]]
    val result = header.parse("   ")

    result.isLeft mustBe true
    result.left.toOption.get.message must include("empty")
  }

  it should "render header value correctly" in {
    val header = implicitly[Header[`X-Request-ID`, Header.Single]]
    val requestId = `X-Request-ID`("test-id-456")

    header.value(requestId) mustBe "test-id-456"
  }

  it should "have correct header name" in {
    val header = implicitly[Header[`X-Request-ID`, Header.Single]]

    header.name.toString mustBe "X-Request-ID"
  }

  it should "handle UUID-style request IDs" in {
    val header = implicitly[Header[`X-Request-ID`, Header.Single]]
    val uuid = "550e8400-e29b-41d4-a716-446655440000"
    val result = header.parse(uuid)

    result mustBe Right(`X-Request-ID`(uuid))
  }

  it should "handle special characters in request ID" in {
    val header = implicitly[Header[`X-Request-ID`, Header.Single]]
    val result = header.parse("req_123-abc.xyz")

    result mustBe Right(`X-Request-ID`("req_123-abc.xyz"))
  }

  "X-User-ID" should "store a valid user ID value" in {
    val userId = `X-User-ID`("user-123")

    userId.value mustBe "user-123"
  }

  it should "parse valid header value" in {
    val header = implicitly[Header[`X-User-ID`, Header.Single]]
    val result = header.parse("my-user-id")

    result mustBe Right(`X-User-ID`("my-user-id"))
  }

  it should "reject empty header value" in {
    val header = implicitly[Header[`X-User-ID`, Header.Single]]
    val result = header.parse("")

    result.isLeft mustBe true
    result.left.toOption.get.message must include("empty")
  }

  it should "reject whitespace-only header value" in {
    val header = implicitly[Header[`X-User-ID`, Header.Single]]
    val result = header.parse("   ")

    result.isLeft mustBe true
    result.left.toOption.get.message must include("empty")
  }

  it should "render header value correctly" in {
    val header = implicitly[Header[`X-User-ID`, Header.Single]]
    val userId = `X-User-ID`("user-456")

    header.value(userId) mustBe "user-456"
  }

  it should "have correct header name" in {
    val header = implicitly[Header[`X-User-ID`, Header.Single]]

    header.name.toString mustBe "X-User-ID"
  }

  it should "handle UUID-style user IDs" in {
    val header = implicitly[Header[`X-User-ID`, Header.Single]]
    val uuid = "550e8400-e29b-41d4-a716-446655440000"
    val result = header.parse(uuid)

    result mustBe Right(`X-User-ID`(uuid))
  }

  it should "handle email-style user IDs" in {
    val header = implicitly[Header[`X-User-ID`, Header.Single]]
    val result = header.parse("user@example.com")

    result mustBe Right(`X-User-ID`("user@example.com"))
  }

  "X-Request-ID and X-User-ID" should "support equality" in {
    val requestId1 = `X-Request-ID`("req-123")
    val requestId2 = `X-Request-ID`("req-123")
    val requestId3 = `X-Request-ID`("req-456")

    requestId1 mustBe requestId2
    requestId1 must not be requestId3

    val userId1 = `X-User-ID`("user-123")
    val userId2 = `X-User-ID`("user-123")
    val userId3 = `X-User-ID`("user-456")

    userId1 mustBe userId2
    userId1 must not be userId3
  }

  it should "have correct hash codes" in {
    val requestId1 = `X-Request-ID`("req-123")
    val requestId2 = `X-Request-ID`("req-123")

    requestId1.hashCode mustBe requestId2.hashCode

    val userId1 = `X-User-ID`("user-123")
    val userId2 = `X-User-ID`("user-123")

    userId1.hashCode mustBe userId2.hashCode
  }
}
