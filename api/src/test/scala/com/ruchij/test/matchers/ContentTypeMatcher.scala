package com.ruchij.test.matchers

import org.http4s.headers.`Content-Type`
import org.http4s.{MediaType, Response}
import org.scalatest.matchers.{MatchResult, Matcher}

class ContentTypeMatcher[F[_]](mediaType: MediaType) extends Matcher[Response[F]] {
  override def apply(response: Response[F]): MatchResult = {
    val contentType: Option[MediaType] = response.headers.get(`Content-Type`).map(_.mediaType)

    MatchResult(
      contentType.contains(mediaType),
      s"""
        |Expected Content-Type: $mediaType
        |
        |Actual Content-Type: ${contentType.map(_.toString).getOrElse("Missing Content-Type header")}
        |""".stripMargin,
      s"Content-Type is $mediaType"
    )
  }
}
