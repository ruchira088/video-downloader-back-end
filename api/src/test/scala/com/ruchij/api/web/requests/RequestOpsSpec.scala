package com.ruchij.api.web.requests

import cats.effect.IO
import com.ruchij.core.test.IOSupport.runIO
import io.circe.generic.auto._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.implicits._
import org.http4s.{ContextRequest, Method, Request}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class RequestOpsSpec extends AnyFlatSpec with Matchers {

  case class TestEntity(name: String, value: Int)

  "RequestOps.to" should "decode and validate a request body" in runIO {
    val entity = TestEntity("test", 42)
    val request = Request[IO](Method.POST, uri"/test").withEntity(entity)

    RequestOps.to[IO, TestEntity](request).map { result =>
      result.name mustBe "test"
      result.value mustBe 42
    }
  }

  "RequestOpsSyntax" should "provide an implicit .to method on Request" in runIO {
    import RequestOps._

    val entity = TestEntity("syntax-test", 100)
    val request = Request[IO](Method.POST, uri"/test").withEntity(entity)

    request.to[TestEntity].map { result =>
      result.name mustBe "syntax-test"
      result.value mustBe 100
    }
  }

  "ContextRequestOpsSyntax" should "provide an implicit .to method on ContextRequest" in runIO {
    import RequestOps._

    val entity = TestEntity("context-test", 200)
    val request = Request[IO](Method.POST, uri"/test").withEntity(entity)
    val contextRequest = ContextRequest("context-value", request)

    contextRequest.to[TestEntity].map { result =>
      result.name mustBe "context-test"
      result.value mustBe 200
    }
  }

  it should "work with different context types" in runIO {
    import RequestOps._

    val entity = TestEntity("int-context", 300)
    val request = Request[IO](Method.POST, uri"/test").withEntity(entity)
    val contextRequest = ContextRequest(12345, request)

    contextRequest.to[TestEntity].map { result =>
      result.name mustBe "int-context"
      result.value mustBe 300
    }
  }

  "Validator.noValidator" should "pass through any value unchanged" in runIO {
    val validator = Validator.noValidator[IO]
    val entity = TestEntity("no-validation", 999)

    validator.validate(entity).map { result =>
      result mustBe entity
    }
  }
}
