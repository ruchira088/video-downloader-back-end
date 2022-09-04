package com.ruchij.api.web.requests

import cats.{Applicative, ApplicativeError}
import com.ruchij.core.daos.workers.models.WorkerStatus
import com.ruchij.core.exceptions.ValidationException

final case class WorkerStatusUpdateRequest(workerStatus: WorkerStatus)

object WorkerStatusUpdateRequest {
  private val ValidUpdateStatuses = Seq(WorkerStatus.Paused, WorkerStatus.Available)

  implicit def workerStatusUpdateRequestValidator[F[_]: ApplicativeError[*[_], Throwable]]: Validator[F, WorkerStatusUpdateRequest] =
    new Validator[F, WorkerStatusUpdateRequest] {
      override def validate[B <: WorkerStatusUpdateRequest](workerStatusUpdateRequest: B): F[B] =
        if (ValidUpdateStatuses.contains(workerStatusUpdateRequest.workerStatus))
          Applicative[F].pure(workerStatusUpdateRequest)
        else ApplicativeError[F, Throwable].raiseError {
          ValidationException(s"Invalid status value: ${workerStatusUpdateRequest.workerStatus}. Valid statuses are ${ValidUpdateStatuses.mkString("[ ", ", ", " ]")}")
        }

    }
}