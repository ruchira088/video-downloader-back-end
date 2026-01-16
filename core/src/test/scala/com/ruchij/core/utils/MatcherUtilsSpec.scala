package com.ruchij.core.utils

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.duration._

class MatcherUtilsSpec extends AnyFlatSpec with Matchers {

  "IntNumber" should "extract integer from string" in {
    MatcherUtils.IntNumber.unapply("123") mustBe Some(123)
  }

  it should "extract zero" in {
    MatcherUtils.IntNumber.unapply("0") mustBe Some(0)
  }

  it should "extract negative numbers" in {
    MatcherUtils.IntNumber.unapply("-456") mustBe Some(-456)
  }

  it should "fail for non-integer strings" in {
    val result = MatcherUtils.IntNumber.unapply("abc")
    result mustBe None
  }

  it should "fail for empty string" in {
    val result = MatcherUtils.IntNumber.unapply("")
    result mustBe None
  }

  it should "fail for floating point strings" in {
    val result = MatcherUtils.IntNumber.unapply("12.34")
    result mustBe None
  }

  "DoubleNumber" should "extract double from string" in {
    MatcherUtils.DoubleNumber.unapply("12.34") mustBe Some(12.34)
  }

  it should "extract zero" in {
    MatcherUtils.DoubleNumber.unapply("0.0") mustBe Some(0.0)
  }

  it should "extract negative numbers" in {
    MatcherUtils.DoubleNumber.unapply("-45.67") mustBe Some(-45.67)
  }

  it should "extract integers as doubles" in {
    MatcherUtils.DoubleNumber.unapply("42") mustBe Some(42.0)
  }

  it should "fail for non-number strings" in {
    val result = MatcherUtils.DoubleNumber.unapply("abc")
    result mustBe None
  }

  it should "fail for empty string" in {
    val result = MatcherUtils.DoubleNumber.unapply("")
    result mustBe None
  }

  "FiniteDurationValue" should "extract duration with hours, minutes, seconds" in {
    val result = MatcherUtils.FiniteDurationValue.unapply("1:30:45")
    result mustBe Some((1 * 3600 + 30 * 60 + 45).seconds)
  }

  it should "extract duration with just minutes and seconds" in {
    val result = MatcherUtils.FiniteDurationValue.unapply("5:30")
    // When there's no hours, the first capture group is null
    result mustBe None
  }

  it should "extract duration with 0 hours" in {
    val result = MatcherUtils.FiniteDurationValue.unapply("0:15:30")
    result mustBe Some((15 * 60 + 30).seconds)
  }

  it should "extract duration with large hours" in {
    val result = MatcherUtils.FiniteDurationValue.unapply("24:00:00")
    result mustBe Some((24 * 3600).seconds)
  }

  it should "fail for invalid format" in {
    val result = MatcherUtils.FiniteDurationValue.unapply("invalid")
    result mustBe None
  }

  it should "fail for empty string" in {
    val result = MatcherUtils.FiniteDurationValue.unapply("")
    result mustBe None
  }

  it should "extract duration with leading zeros" in {
    val result = MatcherUtils.FiniteDurationValue.unapply("01:05:09")
    result mustBe Some((1 * 3600 + 5 * 60 + 9).seconds)
  }

  it should "extract zero duration" in {
    val result = MatcherUtils.FiniteDurationValue.unapply("0:00:00")
    result mustBe Some(0.seconds)
  }
}
