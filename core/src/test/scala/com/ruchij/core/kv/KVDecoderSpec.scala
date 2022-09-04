package com.ruchij.core.kv

import cats.effect.IO
import com.ruchij.core.kv.KVDecoderSpec.{KVDecoderStringExtension, Person, Student}
import com.ruchij.core.kv.codecs.KVDecoder
import com.ruchij.core.test.IOSupport.runIO
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class KVDecoderSpec extends AnyFlatSpec with Matchers {

  "Simple final case classes" should "be decoded" in {
    runIO("1::John::Smith".decodeAs[IO, Person]) mustBe Person(1, "John", "Smith")
  }

  it should "fail for malformed String values" in {
    runIO("h::John::Smith".decodeAs[IO, Person].attempt).left.map(_.getMessage) mustBe
      Left("""Unable to parse "h" as a int""")
  }

  it should "fail for longer String values" in {
    runIO("1::John::Smith::Smith".decodeAs[IO, Person].attempt).left.map(_.getMessage) mustBe
      Left("Key contains extra terms: Smith")
  }

  it should "fail for shorted String values" in {
    runIO("1::John".decodeAs[IO, Person].attempt).left.map(_.getMessage) mustBe
      Left("Key is too short")
  }

  "Nested final case classes" should "be decoded" in {
    runIO("1::John::Smith::2::Adam::Smith".decodeAs[IO, Student]) mustBe
      Student(Person(1, "John", "Smith"), Person(2, "Adam", "Smith"))
  }

  it should "fail for malformed String values" in {
    runIO("1::John::Smith::true::Adam::Smith".decodeAs[IO, Student].attempt).left.map(_.getMessage) mustBe
      Left("""Unable to parse "true" as a int""")
  }

  it should "fail for longer String values" in {
    runIO("1::John::Smith::2::Adam::Smith::Smith".decodeAs[IO, Student].attempt).left.map(_.getMessage) mustBe
      Left("Key contains extra terms: Smith")
  }

  it should "fail for shorter String values" in {
    runIO("1::John::Smith::2::Adam".decodeAs[IO, Student].attempt).left.map(_.getMessage) mustBe
      Left("Key is too short")
  }

}

object KVDecoderSpec {
  final case class Person(id: Int, firstName: String, lastName: String)
  final case class Student(self: Person, teacher: Person)

  implicit class KVDecoderStringExtension(string: String) {
    def decodeAs[F[_], A](implicit decoder: KVDecoder[F, A]): F[A] = decoder.decode(string)
  }
}
