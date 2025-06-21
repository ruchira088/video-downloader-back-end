package com.ruchij.core.test

package object matchers {
  def matchCaseInsensitivelyTo(expected: String): CaseInsensitiveStringMatcher =
    new CaseInsensitiveStringMatcher(expected)
}
