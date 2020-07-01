package com.ruchij.test.utils

import cats.effect.Sync
import cats.implicits._
import com.ruchij.types.FunctionKTypes.eitherToF
import io.circe.Json
import io.circe.parser.{parse => parseJson}
import org.http4s.Response

object JsonUtils {
  def fromResponse[F[_]: Sync](response: Response[F]): F[Json] =
    response.bodyText
      .compile[F, F, String]
      .string
      .flatMap { text =>
        eitherToF[Throwable, F].apply(parseJson(text))
      }
}
