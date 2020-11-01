package com.ruchij.api.web.requests

import cats.{Applicative, ApplicativeError}
import com.ruchij.core.daos.scheduling.models.SchedulingStatus
import com.ruchij.core.exceptions.ValidationException

case class UpdateScheduledVideoRequest(status: SchedulingStatus)

object UpdateScheduledVideoRequest {
  val ValidInputStatuses = List(SchedulingStatus.Queued, SchedulingStatus.Paused)

  implicit def updateScheduledVideoRequestValidator[F[_]: ApplicativeError[*[_], Throwable]]
    : Validator[F, UpdateScheduledVideoRequest] =
    new Validator[F, UpdateScheduledVideoRequest] {
      override def validate[B <: UpdateScheduledVideoRequest](value: B): F[B] =
        if (ValidInputStatuses.contains(value.status))
          Applicative[F].pure(value)
        else
          ApplicativeError[F, Throwable].raiseError(
            ValidationException(
              s"Invalid status value: ${value.status}. Valid values are [${ValidInputStatuses.mkString(", ")}]"
            )
          )
    }
}
