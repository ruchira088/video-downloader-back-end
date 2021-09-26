package com.ruchij.api.web.headers

import org.http4s.{Header, ParseFailure}
import org.typelevel.ci.CIStringSyntax

case class `X-Request-ID` private (value: String)

object `X-Request-ID` {
  implicit val headerInstance: Header[`X-Request-ID`, Header.Single] =
    Header.create[`X-Request-ID`, Header.Single](
      ci"X-Request-ID",
      _.value,
      input =>
        if (input.trim.isEmpty)
          Left(ParseFailure("X-Request-ID header value is empty", "X-Request-ID header value cannot be empty"))
        else Right(`X-Request-ID`(input))
    )
}
