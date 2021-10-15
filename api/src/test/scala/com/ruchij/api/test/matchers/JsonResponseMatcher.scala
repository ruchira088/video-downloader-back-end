package com.ruchij.api.test.matchers

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.ruchij.api.test.utils.JsonUtils
import io.circe.Json
import org.http4s.Response
import org.scalatest.matchers.{MatchResult, Matcher}

class JsonResponseMatcher(expectedJson: Json)
    extends Matcher[Response[IO]] {
  override def apply(response: Response[IO]): MatchResult = {
    val json: Json = JsonUtils.fromResponse(response).unsafeRunSync()

    MatchResult(
      expectedJson == json,
      s"""
        |Expected: $expectedJson
        |
        |Actual: $json
        |""".stripMargin, "JSON values are equal"
    )
  }
}
