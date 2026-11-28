package com.ruchij.api.web.responses

import cats.data.NonEmptyList

final case class ErrorResponse(errorMessages: NonEmptyList[String])
