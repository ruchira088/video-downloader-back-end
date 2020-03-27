package com.ruchij.circe

import io.circe.Decoder
import org.joda.time.DateTime

import scala.util.Try

object Decoders {
  implicit val dateTimeDecoder: Decoder[DateTime] =
    Decoder.decodeString.emapTry(dateTimeString => Try(DateTime.parse(dateTimeString)))
}
