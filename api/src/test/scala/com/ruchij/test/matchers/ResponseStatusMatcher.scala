package com.ruchij.test.matchers

import org.http4s.{Response, Status}
import org.scalatest.matchers.{MatchResult, Matcher}

class ResponseStatusMatcher[F[_]](status: Status) extends Matcher[Response[F]] {
  override def apply(response: Response[F]): MatchResult =
    MatchResult(
      status == response.status,
      s"""
         |Expected: $status
         |
         |Actual: ${response.status}
         |""".stripMargin,
      s"Expected and actual statuses are equal ${response.status}"
    )
}
