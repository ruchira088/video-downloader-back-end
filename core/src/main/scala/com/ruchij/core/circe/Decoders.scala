package com.ruchij.core.circe

import com.ruchij.core.daos.videometadata.models.VideoSite
import enumeratum.{Enum, EnumEntry}
import io.circe.Decoder
import org.joda.time.DateTime
import shapeless.{::, Generic, HNil}

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

object Decoders {
  implicit val dateTimeDecoder: Decoder[DateTime] =
    Decoder.decodeString.emapTry(dateTimeString => Try(DateTime.parse(dateTimeString)))

  implicit val finiteDurationDecoder: Decoder[FiniteDuration] =
    Decoder.decodeDuration.map { duration => FiniteDuration(duration.toMillis, TimeUnit.MILLISECONDS) }

  implicit val videoSiteDecoder: Decoder[VideoSite] =
    Decoder.decodeString.map(VideoSite.from)

  implicit def enumDecoder[A <: EnumEntry](implicit enumValues: Enum[A]): Decoder[A] =
    Decoder.decodeString.emap { enumString =>
      enumValues.withNameInsensitiveEither(enumString).left.map(_.getMessage)
    }

  implicit def stringWrapperDecoder[A <: AnyVal](implicit generic: Generic.Aux[A, String :: HNil]): Decoder[A] =
    Decoder.decodeString.emap {
      value => if (value.trim.isEmpty) Left("Cannot be empty") else Right(generic.from(value :: HNil))
    }
}
