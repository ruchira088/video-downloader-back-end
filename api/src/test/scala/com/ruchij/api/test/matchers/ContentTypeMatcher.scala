package com.ruchij.api.test.matchers

import cats.Show
import org.http4s.headers.`Content-Type`
import org.http4s.{MediaType, Response}
import org.scalatest.matchers.{MatchResult, Matcher}

class ContentTypeMatcher[F[_]](mediaType: MediaType) extends Matcher[Response[F]] {
  override def apply(response: Response[F]): MatchResult = {
    val maybeMediaType: Option[MediaType] = response.headers.get[`Content-Type`].map(_.mediaType)

    MatchResult(
      maybeMediaType.contains(mediaType),
      s"""
        |Expected Content-Type: ${Show[MediaType].show(mediaType)}
        |
        |Actual Content-Type: ${maybeMediaType.map(Show[MediaType].show).getOrElse("Missing Content-Type header")}
        |""".stripMargin,
      s"Content-Type is ${Show[MediaType].show(mediaType)}"
    )
  }
}
