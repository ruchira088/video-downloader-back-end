package com.ruchij.core.types

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class NamedToStringSpec extends AnyFlatSpec with Matchers {
  "toString" should "have named properties" in {
    final case class Name(firstName: String, lastName: String) extends NamedToString
    final case class Person(name: Name, age: Int, wife: Option[Person]) extends NamedToString
    val jane = Person(Name("Jane", "Doe"), 25, None)
    val person = Person(Name("John", "Smith"), 25, Some(jane))

    val expectedString =
    """Person (
       |	name = Name (
       |		firstName = John,
       |		lastName = Smith
       |	),
       |	age = 25,
       |	wife = Some (
       |		Person (
       |			name = Name (
       |				firstName = Jane,
       |				lastName = Doe
       |			),
       |			age = 25,
       |			wife = None
       |		)
       |	)
       |)""".stripMargin

    person.toString shouldEqual expectedString
  }
}
