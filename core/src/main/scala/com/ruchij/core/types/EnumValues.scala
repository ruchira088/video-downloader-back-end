package com.ruchij.core.types

import scala.compiletime.{erasedValue, summonFrom}
import scala.deriving.Mirror
import scala.reflect.ClassTag

// A value of an enum whose name is stored in PostgreSQL, Redis and messages, and accepted in query parameters.
// The name is the value's own name, unless the value overrides it.
trait EnumEntry {
  def entryName: String = toString
}

// The companion of an enum, which looks up its values by name. A Scala 3 enum's companion implements `values`
// itself; the companion of a sealed hierarchy of case objects implements it with `EnumValues.of`.
trait EnumValues[A <: EnumEntry] { self =>
  def values: Array[A]

  given EnumValues[A] = self

  def withNameOption(name: String): Option[A] = values.find(_.entryName == name)

  // Lower cases both names, as enumeratum did. equalsIgnoreCase would also accept names that differ in characters
  // such as the dotless i, which earlier releases rejected.
  def withNameInsensitiveOption(name: String): Option[A] =
    values.find(_.entryName.toLowerCase == name.toLowerCase)

  def withNameInsensitiveEither(name: String): Either[NoSuchElementException, A] =
    withNameInsensitiveOption(name).toRight(noSuchMember(name))

  def withName(name: String): A = withNameOption(name).getOrElse(throw noSuchMember(name))

  def withNameInsensitive(name: String): A = withNameInsensitiveEither(name).fold(throw _, identity)

  private def noSuchMember(name: String): NoSuchElementException =
    new NoSuchElementException(s"$name is not a member of Enum (${values.map(_.entryName).mkString(", ")})")
}

object EnumValues {
  // The case objects of a sealed hierarchy, including those under nested sealed traits, in declaration order
  inline def of[A](using mirror: Mirror.SumOf[A], classTag: ClassTag[A]): Array[A] =
    caseObjects[A, mirror.MirroredElemTypes].toArray

  inline def caseObjects[A, Types <: Tuple]: List[A] =
    inline erasedValue[Types] match {
      case _: EmptyTuple => Nil

      case _: (head *: tail) =>
        val heads: List[A] =
          summonFrom {
            case caseObject: ValueOf[`head`] => List(caseObject.value.asInstanceOf[A])
            case mirror: Mirror.SumOf[`head`] => caseObjects[A, mirror.MirroredElemTypes]
          }

        heads ::: caseObjects[A, tail]
    }
}
