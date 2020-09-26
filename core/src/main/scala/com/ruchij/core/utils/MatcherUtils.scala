package com.ruchij.core.utils

object MatcherUtils {
  object IntNumber {
    def unapply(text: String): Option[Int] = text.toIntOption
  }
}
