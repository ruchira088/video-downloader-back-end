package com.ruchij.core.exceptions

import cats.data.NonEmptyList

case class AggregatedException[+A <: Exception](errors: NonEmptyList[Exception]) extends Exception
