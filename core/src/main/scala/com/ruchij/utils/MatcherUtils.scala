package com.ruchij.utils

object MatcherUtils {
  object IntNumber {
    def unapply(text: String): Option[Int] = text.toIntOption
  }
}
