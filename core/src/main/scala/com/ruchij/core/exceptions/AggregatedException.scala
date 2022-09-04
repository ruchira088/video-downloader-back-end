package com.ruchij.core.exceptions

import cats.data.NonEmptyList

final case class AggregatedException[+A <: Exception](errors: NonEmptyList[Exception]) extends Exception
