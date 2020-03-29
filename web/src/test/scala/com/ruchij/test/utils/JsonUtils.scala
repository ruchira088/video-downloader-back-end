package com.ruchij.test.utils

import cats.effect.Sync
import cats.implicits._
import cats.~>
import io.circe.Json
import io.circe.parser.{parse => parseJson}
import org.http4s.Response

object JsonUtils {
  def fromResponse[F[_]: Sync](response: Response[F])(implicit functionK: Either[Throwable, *] ~> F): F[Json] =
    response.bodyAsText.compile[F, F, String].string
      .flatMap {
        text => functionK(parseJson(text))
      }
}
