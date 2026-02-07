package com.ruchij.core.types

import java.time.{Instant, ZoneOffset, ZonedDateTime}

object TimeUtils {
  def instantOf(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0, millis: Int = 0): Instant =
    ZonedDateTime
      .of(year, month, day, hour, minute, second, millis * 1000000, ZoneOffset.UTC)
      .toInstant
}
