package com.ruchij.api.web.headers

import org.http4s.{Header, ParseFailure}
import org.typelevel.ci.CIStringSyntax

final case class `X-Request-ID` (value: String)

object `X-Request-ID` {
  implicit val requestIdHeaderInstance: Header[`X-Request-ID`, Header.Single] =
    Header.create[`X-Request-ID`, Header.Single](
      ci"X-Request-ID",
      _.value,
      input =>
        if (input.trim.isEmpty)
          Left(ParseFailure("X-Request-ID header value is empty", "X-Request-ID header value cannot be empty"))
        else Right(`X-Request-ID`(input))
    )
}
