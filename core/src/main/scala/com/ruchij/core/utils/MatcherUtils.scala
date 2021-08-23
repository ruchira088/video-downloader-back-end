package com.ruchij.core.utils

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.util.matching.Regex

object MatcherUtils {
  object IntNumber {
    def unapply(text: String): Option[Int] = text.toIntOption
  }

  object DoubleNumber {
    def unapply(text: String): Option[Double] = text.toDoubleOption
  }

  object FiniteDurationValue {
    private val FiniteDurationPattern: Regex = "(\\d+)?:?(\\d+):(\\d+)".r

    def unapply(text: String): Option[FiniteDuration] =
      text match {
        case FiniteDurationPattern(IntNumber(hours), IntNumber(minutes), IntNumber(seconds)) =>
          Some(FiniteDuration(3600 * hours + minutes * 60 + seconds, TimeUnit.SECONDS))

        case _ => None
      }
  }
}
