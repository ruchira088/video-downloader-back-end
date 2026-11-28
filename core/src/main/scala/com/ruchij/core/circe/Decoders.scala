package com.ruchij.core.circe

import com.ruchij.core.daos.videometadata.models.VideoSite
import com.ruchij.core.types.{EnumEntry, EnumValues}
import io.circe.Decoder
import org.http4s.{MediaType, Method, Status}

import java.time.Instant
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

object Decoders {
  given instantDecoder: Decoder[Instant] =
    Decoder.decodeString.emapTry(instantString => Try(Instant.parse(instantString)))

  // Accepts an ISO-8601 duration string, and the {"length", "unit"} object that Encoders.finiteDurationEncoder
  // produces, so that JSON encoded with Encoders (e.g. pub-sub messages) decodes back.
  given finiteDurationDecoder: Decoder[FiniteDuration] =
    Decoder.decodeDuration
      .map { duration => FiniteDuration(duration.toMillis, TimeUnit.MILLISECONDS) }
      .or {
        Decoder.forProduct2[(Long, String), Long, String]("length", "unit")((_, _)).emapTry { case (length, unit) =>
          Try(FiniteDuration(length, TimeUnit.valueOf(unit)))
        }
      }

  given videoSiteDecoder: Decoder[VideoSite] =
    Decoder.decodeString.map(VideoSite.from)

  given mediaTypeDecoder: Decoder[MediaType] =
    Decoder.decodeString.emap(MediaType.parse(_).left.map(_.getMessage))

  given methodDecoder: Decoder[Method] = Decoder.decodeString.emap(Method.fromString(_).left.map(_.message))

  given statusDecoder: Decoder[Status] = Decoder.decodeInt.emap(Status.fromInt(_).left.map(_.message))

  given enumDecoder[A <: EnumEntry](using enumValues: EnumValues[A]): Decoder[A] =
    Decoder.decodeString.emap { enumString =>
      enumValues.withNameInsensitiveEither(enumString).left.map(_.getMessage)
    }

  // Decodes a non-empty string into a value class that wraps it
  def stringWrapperDecoder[A](wrap: String => A): Decoder[A] =
    Decoder.decodeString.emap { value =>
      if (value.trim.isEmpty) Left("Cannot be empty") else Right(wrap(value))
    }
}
