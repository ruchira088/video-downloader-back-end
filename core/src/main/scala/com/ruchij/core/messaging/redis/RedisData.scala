package com.ruchij.core.messaging.redis

import com.ruchij.core.exceptions.ValidationException
import com.ruchij.core.messaging.redis.RedisData.DATA_KEY
import io.circe.{Decoder, Encoder}
import io.circe.parser.parse

final case class RedisData[A](data: A) {
  def toMap(implicit encoder: Encoder[A]): Map[String, String] =
    Map(DATA_KEY -> encoder(data).spaces2)
}

object RedisData {
  private val DATA_KEY: String = "data"

  def from[A](data: Map[String, String])(implicit decoder: Decoder[A]): Either[Exception, A] =
    data.get(DATA_KEY).toRight(ValidationException(s"$data doesn't contain $DATA_KEY"))
      .flatMap(parse)
      .flatMap(decoder.decodeJson)
}