package com.ruchij.api.circe

import enumeratum.{Enum, EnumEntry}
import io.circe.Decoder
import org.joda.time.DateTime
import shapeless.{::, Generic, HNil}

import scala.util.Try

object Decoders {
  implicit val dateTimeDecoder: Decoder[DateTime] =
    Decoder.decodeString.emapTry(dateTimeString => Try(DateTime.parse(dateTimeString)))

  implicit def enumDecoder[A <: EnumEntry](implicit enumValues: Enum[A]): Decoder[A] =
    Decoder.decodeString.emap { enumString =>
      enumValues.withNameInsensitiveEither(enumString).left.map(_.getMessage)
    }

  implicit def stringWrapperDecoder[A](implicit generic: Generic.Aux[A, String :: HNil]): Decoder[A] =
    Decoder.decodeString.emap {
      value => if (value.trim.isEmpty) Left("Cannot be empty") else Right(generic.from(value :: HNil))
    }
}
