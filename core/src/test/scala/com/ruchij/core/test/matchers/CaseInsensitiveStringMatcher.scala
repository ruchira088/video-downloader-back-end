package com.ruchij.core.test.matchers

import org.scalatest.matchers.{MatchResult, Matcher}

class CaseInsensitiveStringMatcher(right: String) extends Matcher[String]{
  override def apply(left: String): MatchResult =
    MatchResult(
      right.toLowerCase == left.toLowerCase,
      s"${left.toLowerCase} did not equal ${right.toLowerCase}",
      s"${left.toLowerCase} equal ${right.toLowerCase}"
    )
}
