package com.ruchij.core.services.video.models

import scala.concurrent.duration.FiniteDuration

case class DurationRange(min: Option[FiniteDuration], max: Option[FiniteDuration])

object DurationRange {
  val All: DurationRange = DurationRange(None, None)

  def create(
    min: Option[FiniteDuration],
    max: Option[FiniteDuration]
  ): Either[IllegalArgumentException, DurationRange] =
    min
      .flatMap { minimum =>
        max.flatMap { maximum =>
          if (maximum >= minimum) None
          else Some(Left(new IllegalArgumentException(s"Minimum ($minimum) is greater than maximum ($maximum)")))
        }
      }
      .getOrElse(Right(DurationRange(min, max)))
}
