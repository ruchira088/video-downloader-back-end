package com.ruchij.core.formats

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

// An enum value's name is what PostgreSQL rows, Redis values, Kafka and pub-sub messages, and query parameters hold,
// so renaming a value, or changing how its name is derived, breaks data written by an earlier release. Each module
// lists the names of its enums here, in declaration order, and checks that a name parses back regardless of case.
abstract class EnumNamesSpec(enums: List[EnumNames]) extends AnyFlatSpec with Matchers {
  "enums" must "have distinct names" in {
    enums.map(_.name).distinct.size mustBe enums.size
  }

  enums.foreach { enumNames =>
    enumNames.name must "keep the names of its values" in {
      enumNames.actual mustBe enumNames.expected
    }

    it must "parse each name regardless of case" in {
      enumNames.expected.foreach { name =>
        enumNames.parse(name) mustBe Some(name)
        enumNames.parse(name.toUpperCase) mustBe Some(name)
        enumNames.parse(name.toLowerCase) mustBe Some(name)
      }

      enumNames.parse("not-a-value") mustBe None
    }
  }
}

final case class EnumNames(name: String, actual: List[String], expected: List[String], parse: String => Option[String])

object EnumNames {
  def apply[A](name: String, values: Seq[A], expected: List[String])(
    entryName: A => String,
    parse: String => Option[A]
  ): EnumNames =
    EnumNames(name, values.map(entryName).toList, expected, input => parse(input).map(entryName))
}
