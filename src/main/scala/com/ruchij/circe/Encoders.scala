package com.ruchij.circe

import java.time.Duration

import enumeratum.EnumEntry
import io.circe.Encoder
import org.joda.time.DateTime

import scala.concurrent.duration.FiniteDuration

object Encoders {
  implicit val dateTimeEncoder: Encoder[DateTime] = Encoder.encodeString.contramap[DateTime](_.toString)

  implicit def throwableEncoder[A <: Throwable]: Encoder[A] =
    Encoder.encodeString.contramap[A](_.getMessage)

  implicit def enumEncoder[A <: EnumEntry]: Encoder[A] = Encoder.encodeString.contramap[A](_.entryName)

  implicit val finiteDurationEncoder: Encoder[FiniteDuration] =
    Encoder.encodeDuration.contramap[FiniteDuration](finiteDuration => Duration.ofSeconds(finiteDuration.toSeconds))
}
