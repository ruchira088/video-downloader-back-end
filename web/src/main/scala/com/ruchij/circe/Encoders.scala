package com.ruchij.circe

import java.nio.file.Path
import java.time.Duration

import cats.Show
import enumeratum.EnumEntry
import io.circe.Encoder
import org.http4s.MediaType
import org.joda.time.DateTime

import scala.concurrent.duration.FiniteDuration

object Encoders {
  implicit val dateTimeEncoder: Encoder[DateTime] = Encoder.encodeString.contramap[DateTime](_.toString)

  implicit def throwableEncoder[A <: Throwable]: Encoder[A] =
    Encoder.encodeString.contramap[A](_.getMessage)

  implicit def enumEncoder[A <: EnumEntry]: Encoder[A] = Encoder.encodeString.contramap[A](_.entryName)

  implicit val finiteDurationEncoder: Encoder[FiniteDuration] =
    Encoder.encodeLong.contramap[FiniteDuration](_.toSeconds)

  implicit val pathEncoder: Encoder[Path] = Encoder.encodeString.contramap[Path](_.toAbsolutePath.toString)

  implicit val mediaTypeEncoder: Encoder[MediaType] = Encoder.encodeString.contramap[MediaType](Show[MediaType].show)
}
