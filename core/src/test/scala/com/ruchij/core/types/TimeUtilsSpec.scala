package com.ruchij.core.types

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.time.{Instant, ZoneOffset, ZonedDateTime}

class TimeUtilsSpec extends AnyFlatSpec with Matchers {

  "instantOf" should "create an Instant from date and time components" in {
    val instant = TimeUtils.instantOf(2024, 6, 15, 14, 30, 45, 500)

    val expected = ZonedDateTime.of(2024, 6, 15, 14, 30, 45, 500 * 1000000, ZoneOffset.UTC).toInstant
    instant mustBe expected
  }

  it should "default seconds and millis to 0" in {
    val instant = TimeUtils.instantOf(2024, 1, 1, 0, 0)

    val expected = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant
    instant mustBe expected
  }

  it should "use UTC timezone" in {
    val instant = TimeUtils.instantOf(2024, 12, 31, 23, 59, 59, 999)

    val zonedDateTime = ZonedDateTime.ofInstant(instant, ZoneOffset.UTC)
    zonedDateTime.getYear mustBe 2024
    zonedDateTime.getMonthValue mustBe 12
    zonedDateTime.getDayOfMonth mustBe 31
    zonedDateTime.getHour mustBe 23
    zonedDateTime.getMinute mustBe 59
    zonedDateTime.getSecond mustBe 59
  }

  it should "handle epoch start" in {
    val instant = TimeUtils.instantOf(1970, 1, 1, 0, 0, 0, 0)
    instant mustBe Instant.EPOCH
  }

  it should "convert millis to nanoseconds correctly" in {
    val instant = TimeUtils.instantOf(2024, 1, 1, 0, 0, 0, 123)
    val nanos = instant.getNano
    nanos mustBe 123000000
  }

  it should "handle various valid dates" in {
    // Leap year
    val leapDay = TimeUtils.instantOf(2024, 2, 29, 12, 0)
    leapDay mustBe a[Instant]

    // End of year
    val endOfYear = TimeUtils.instantOf(2024, 12, 31, 23, 59)
    endOfYear mustBe a[Instant]

    // Start of year
    val startOfYear = TimeUtils.instantOf(2024, 1, 1, 0, 0)
    startOfYear mustBe a[Instant]
  }
}
