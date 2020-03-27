package com.ruchij.test.matchers

import cats.effect.Sync
import cats.~>
import com.ruchij.test.utils.JsonUtils
import com.ruchij.types.UnsafeExtractor
import io.circe.Json
import org.http4s.Response
import org.scalatest.matchers.{MatchResult, Matcher}

class JsonResponseMatcher[F[_]: Sync: UnsafeExtractor: Lambda[X[_] => Either[Throwable, *] ~> X]](expectedJson: Json)
    extends Matcher[Response[F]] {
  override def apply(response: Response[F]): MatchResult = {
    val json: Json = UnsafeExtractor.extractUnsafely(JsonUtils.fromResponse(response))

    MatchResult(expectedJson == json, s"""
        |Expected: $expectedJson
        |
        |Actual: $json
        |""".stripMargin, "JSON values are equal")
  }
}
