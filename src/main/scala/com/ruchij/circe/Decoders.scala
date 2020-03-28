package com.ruchij.circe

import io.circe.Decoder
import org.http4s.Uri
import org.joda.time.DateTime

import scala.util.Try

object Decoders {
  implicit val dateTimeDecoder: Decoder[DateTime] =
    Decoder.decodeString.emapTry(dateTimeString => Try(DateTime.parse(dateTimeString)))

  implicit val uriDecoder: Decoder[Uri] =
    Decoder.decodeString.emap(uriString => Uri.fromString(uriString).left.map(_.details))
}
