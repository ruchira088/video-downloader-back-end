package com.ruchij.test.matchers

import cats.effect.{Effect, Sync}
import com.ruchij.test.utils.JsonUtils
import io.circe.Json
import org.http4s.Response
import org.scalatest.matchers.{MatchResult, Matcher}

class JsonResponseMatcher[F[_]: Sync: Effect](expectedJson: Json)
    extends Matcher[Response[F]] {
  override def apply(response: Response[F]): MatchResult = {
    val json: Json = Effect[F].toIO(JsonUtils.fromResponse(response)).unsafeRunSync()

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
