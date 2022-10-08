package com.ruchij.api.test.matchers

import org.joda.time.DateTime
import org.scalatest.matchers.{MatchResult, Matcher}

class DateTimeMatcher(expected: DateTime) extends Matcher[DateTime] {
  override def apply(actual: DateTime): MatchResult =
    MatchResult(
      expected.getMillis == actual.getMillis,
      s"$actual does NOT equal $expected",
      "DateTime values are equal"
    )
}
