package com.ruchij.api.web.headers

import org.http4s.{Header, ParseFailure}
import org.typelevel.ci.CIStringSyntax

case class `X-User-ID`(value: String)

object `X-User-ID` {
  implicit val userIdHeaderInstance: Header[`X-User-ID`, Header.Single] =
    Header.create[`X-User-ID`, Header.Single](
      ci"X-User-ID",
      _.value,
      input =>
        if (input.trim.isEmpty) Left(ParseFailure("X-User-ID is empty", "X-User-ID header value cannot be empty"))
        else Right(`X-User-ID`(input))
    )
}
