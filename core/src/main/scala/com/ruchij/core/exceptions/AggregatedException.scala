package com.ruchij.core.exceptions

import cats.data.NonEmptyList

final case class AggregatedException(errors: NonEmptyList[Exception])
    extends Exception(errors.map(_.getMessage).toList.mkString("; "))
