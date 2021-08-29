package com.ruchij.core.daos.scheduling.models

import com.ruchij.core.exceptions.ValidationException

import scala.math.Ordered.orderingToOrdered

case class RangeValue[+A] (min: Option[A], max: Option[A])

object RangeValue {
  def all[A]: RangeValue[A] = RangeValue(None, None)

  def create[A: Ordering](maybeMin: Option[A], maybeMax: Option[A]): Either[ValidationException, RangeValue[A]] =
    maybeMin
      .flatMap { min =>
        maybeMax.flatMap { max =>
          if (max < min) Some(Left(ValidationException(s"Minimum ($min) is greater than maximum ($max)"))) else None
        }
      }
      .getOrElse[Either[ValidationException, RangeValue[A]]](Right(RangeValue(maybeMin, maybeMax)))

}
