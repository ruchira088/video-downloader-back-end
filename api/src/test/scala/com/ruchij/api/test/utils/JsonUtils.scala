package com.ruchij.api.test.utils

import cats.effect.Sync
import cats.implicits.*
import com.ruchij.core.types.FunctionKTypes.*
import io.circe.Json
import io.circe.parser as JsonParser
import org.http4s.Response

object JsonUtils {
  def fromResponse[F[_]: Sync](response: Response[F]): F[Json] =
    response.bodyText
      .compile[F, F, String]
      .string
      .flatMap { text =>
        JsonParser.parse(text).toType[F, Throwable]
      }
}
