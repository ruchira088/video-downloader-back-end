package com.ruchij.core.circe

import java.nio.file.Path
import cats.Show
import com.ruchij.core.daos.videometadata.models.VideoSite
import enumeratum.EnumEntry
import io.circe.{Encoder, Json}
import org.http4s.MediaType
import shapeless.{::, Generic, HNil}

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

object Encoders {
  implicit val instantEncoder: Encoder[Instant] = Encoder.encodeString.contramap[Instant](_.toString)

  implicit def throwableEncoder[A <: Throwable]: Encoder[A] =
    Encoder.encodeString.contramap[A](_.getMessage)

  implicit def enumEncoder[A <: EnumEntry]: Encoder[A] = Encoder.encodeString.contramap[A](_.entryName)

  implicit val finiteDurationEncoder: Encoder[FiniteDuration] =
    (finiteDuration: FiniteDuration) =>
      Json.obj(
        "length" -> Json.fromLong(finiteDuration.length),
        "unit" -> Json.fromString(finiteDuration.unit.name())
      )

  implicit val videoSiteEncoder: Encoder[VideoSite] = Encoder[String].contramap[VideoSite](_.name.toLowerCase)

  implicit val pathEncoder: Encoder[Path] = Encoder.encodeString.contramap[Path](_.toAbsolutePath.toString)

  implicit val mediaTypeEncoder: Encoder[MediaType] = Encoder.encodeString.contramap[MediaType](Show[MediaType].show)

  implicit def stringWrapperEncoder[A <: AnyVal](implicit generic: Generic.Aux[A, String :: HNil]): Encoder[A] =
    Encoder[String].contramap[A](value => generic.to(value).head)
}
