package com.ruchij.exceptions

import cats.data.NonEmptyList

case class AggregatedException[+A <: Exception](errors: NonEmptyList[Exception]) extends Exception
