package com.ruchij.api.services.models

import com.ruchij.api.daos.user.models.{Email, Role, User}
import org.joda.time.DateTime
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class ContextSpec extends AnyFlatSpec with Matchers {

  private val timestamp = new DateTime(2024, 5, 15, 10, 30)
  private val testUser = User("user-1", timestamp, "John", "Doe", Email("john@example.com"), Role.User)

  "RequestContext" should "store requestId" in {
    val context = Context.RequestContext("req-123")

    context.requestId mustBe "req-123"
  }

  it should "implement Context trait" in {
    val context: Context = Context.RequestContext("req-123")

    context.requestId mustBe "req-123"
  }

  "AuthenticatedRequestContext" should "store user and requestId" in {
    val context = Context.AuthenticatedRequestContext(testUser, "req-456")

    context.user mustBe testUser
    context.requestId mustBe "req-456"
  }

  it should "implement Context trait" in {
    val context: Context = Context.AuthenticatedRequestContext(testUser, "req-456")

    context.requestId mustBe "req-456"
  }

  "Context" should "allow pattern matching" in {
    val requestContext: Context = Context.RequestContext("req-123")
    val authContext: Context = Context.AuthenticatedRequestContext(testUser, "req-456")

    val requestResult = requestContext match {
      case Context.RequestContext(id) => s"Request: $id"
      case Context.AuthenticatedRequestContext(user, id) => s"Auth: ${user.id}, $id"
    }

    val authResult = authContext match {
      case Context.RequestContext(id) => s"Request: $id"
      case Context.AuthenticatedRequestContext(user, id) => s"Auth: ${user.id}, $id"
    }

    requestResult mustBe "Request: req-123"
    authResult mustBe "Auth: user-1, req-456"
  }

  it should "support equality" in {
    val context1 = Context.RequestContext("req-123")
    val context2 = Context.RequestContext("req-123")
    val context3 = Context.RequestContext("req-456")

    context1 mustBe context2
    context1 must not be context3
  }

  "AuthenticatedRequestContext" should "support equality with same user" in {
    val context1 = Context.AuthenticatedRequestContext(testUser, "req-123")
    val context2 = Context.AuthenticatedRequestContext(testUser, "req-123")

    context1 mustBe context2
  }
}
