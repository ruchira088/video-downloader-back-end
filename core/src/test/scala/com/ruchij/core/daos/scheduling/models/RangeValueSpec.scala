package com.ruchij.core.daos.scheduling.models

import com.ruchij.core.exceptions.ValidationException
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class RangeValueSpec extends AnyFlatSpec with Matchers {

  "RangeValue.all" should "create a range with no bounds" in {
    val range = RangeValue.all[Int]

    range.min mustBe None
    range.max mustBe None
  }

  "RangeValue.create" should "create a valid range with both bounds" in {
    val result = RangeValue.create(Some(1), Some(10))

    result mustBe Right(RangeValue(Some(1), Some(10)))
  }

  it should "create a valid range with only min bound" in {
    val result = RangeValue.create(Some(5), None)

    result mustBe Right(RangeValue(Some(5), None))
  }

  it should "create a valid range with only max bound" in {
    val result = RangeValue.create[Int](None, Some(100))

    result mustBe Right(RangeValue(None, Some(100)))
  }

  it should "create a valid range with no bounds" in {
    val result = RangeValue.create[Int](None, None)

    result mustBe Right(RangeValue(None, None))
  }

  it should "return ValidationException when min is greater than max" in {
    val result = RangeValue.create(Some(10), Some(5))

    result.isLeft mustBe true
    result.left.exists(_.isInstanceOf[ValidationException]) mustBe true
  }

  it should "allow equal min and max values" in {
    val result = RangeValue.create(Some(5), Some(5))

    result mustBe Right(RangeValue(Some(5), Some(5)))
  }

  it should "work with different orderable types" in {
    // Long
    RangeValue.create(Some(1L), Some(100L)) mustBe Right(RangeValue(Some(1L), Some(100L)))

    // Double
    RangeValue.create(Some(1.5), Some(2.5)) mustBe Right(RangeValue(Some(1.5), Some(2.5)))

    // String (lexicographic ordering)
    RangeValue.create(Some("a"), Some("z")) mustBe Right(RangeValue(Some("a"), Some("z")))
  }

  it should "reject invalid ranges for different types" in {
    // Long
    RangeValue.create(Some(100L), Some(1L)).isLeft mustBe true

    // Double
    RangeValue.create(Some(2.5), Some(1.5)).isLeft mustBe true

    // String
    RangeValue.create(Some("z"), Some("a")).isLeft mustBe true
  }

  "RangeValue" should "be covariant in type parameter" in {
    val intRange: RangeValue[Int] = RangeValue(Some(1), Some(10))
    val anyValRange: RangeValue[AnyVal] = intRange

    anyValRange.min mustBe Some(1)
    anyValRange.max mustBe Some(10)
  }
}
