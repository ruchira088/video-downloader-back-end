package com.ruchij.api.test.matchers

import java.time.Instant
import org.scalatest.matchers.{MatchResult, Matcher}

class InstantMatcher(expected: Instant) extends Matcher[Instant] {
  override def apply(actual: Instant): MatchResult =
    MatchResult(
      expected.toEpochMilli == actual.toEpochMilli,
      s"$actual does NOT equal $expected",
      "Instant values are equal"
    )
}
