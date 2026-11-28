package com.ruchij.core.circe

import java.nio.file.Path
import cats.Show
import com.ruchij.core.daos.videometadata.models.VideoSite
import enumeratum.EnumEntry
import io.circe.{Encoder, Json}
import org.http4s.{MediaType, Method, Status}

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

object Encoders {
  given instantEncoder: Encoder[Instant] = Encoder.encodeString.contramap[Instant](_.toString)

  given throwableEncoder[A <: Throwable]: Encoder[A] =
    Encoder.encodeString.contramap[A](_.getMessage)

  given enumEncoder[A <: EnumEntry]: Encoder[A] = Encoder.encodeString.contramap[A](_.entryName)

  given finiteDurationEncoder: Encoder[FiniteDuration] =
    (finiteDuration: FiniteDuration) =>
      Json.obj("length" -> Json.fromLong(finiteDuration.length), "unit" -> Json.fromString(finiteDuration.unit.name()))

  given videoSiteEncoder: Encoder[VideoSite] = Encoder[String].contramap[VideoSite](_.name.toLowerCase)

  given pathEncoder: Encoder[Path] = Encoder.encodeString.contramap[Path](_.toAbsolutePath.toString)

  given mediaTypeEncoder: Encoder[MediaType] = Encoder.encodeString.contramap[MediaType](Show[MediaType].show)

  given methodEncoder: Encoder[Method] = Encoder.encodeString.contramap(_.name)

  given statusEncoder: Encoder[Status] = Encoder.encodeInt.contramap(_.code)

  // Encodes a value class as the string it wraps
  def stringWrapperEncoder[A](unwrap: A => String): Encoder[A] = Encoder[String].contramap[A](unwrap)
}
