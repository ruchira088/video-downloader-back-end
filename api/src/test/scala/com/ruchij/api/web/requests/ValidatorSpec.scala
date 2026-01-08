package com.ruchij.api.web.requests

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.ruchij.core.daos.scheduling.models.SchedulingStatus
import com.ruchij.core.daos.workers.models.WorkerStatus
import com.ruchij.core.exceptions.ValidationException
import com.ruchij.core.test.IOSupport.runIO
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class ValidatorSpec extends AnyFlatSpec with Matchers {

  "WorkerStatusUpdateRequestValidator" should "accept Paused status" in runIO {
    val request = WorkerStatusUpdateRequest(WorkerStatus.Paused)

    WorkerStatusUpdateRequest.workerStatusUpdateRequestValidator[IO]
      .validate(request)
      .map { result =>
        result mustBe request
      }
  }

  it should "accept Available status" in runIO {
    val request = WorkerStatusUpdateRequest(WorkerStatus.Available)

    WorkerStatusUpdateRequest.workerStatusUpdateRequestValidator[IO]
      .validate(request)
      .map { result =>
        result mustBe request
      }
  }

  it should "reject Active status" in {
    val request = WorkerStatusUpdateRequest(WorkerStatus.Active)

    val result = WorkerStatusUpdateRequest.workerStatusUpdateRequestValidator[IO]
      .validate(request)
      .attempt
      .unsafeRunSync()

    result.isLeft mustBe true
    result.left.toOption.get mustBe a[ValidationException]
    result.left.toOption.get.getMessage must include("Invalid status value")
  }

  it should "reject Reserved status" in {
    val request = WorkerStatusUpdateRequest(WorkerStatus.Reserved)

    val result = WorkerStatusUpdateRequest.workerStatusUpdateRequestValidator[IO]
      .validate(request)
      .attempt
      .unsafeRunSync()

    result.isLeft mustBe true
    result.left.toOption.get mustBe a[ValidationException]
  }

  it should "reject Deleted status" in {
    val request = WorkerStatusUpdateRequest(WorkerStatus.Deleted)

    val result = WorkerStatusUpdateRequest.workerStatusUpdateRequestValidator[IO]
      .validate(request)
      .attempt
      .unsafeRunSync()

    result.isLeft mustBe true
    result.left.toOption.get mustBe a[ValidationException]
  }

  "UpdateScheduledVideoRequestValidator" should "accept Queued status" in runIO {
    val request = UpdateScheduledVideoRequest(SchedulingStatus.Queued)

    UpdateScheduledVideoRequest.updateScheduledVideoRequestValidator[IO]
      .validate(request)
      .map { result =>
        result mustBe request
      }
  }

  it should "accept Paused status" in runIO {
    val request = UpdateScheduledVideoRequest(SchedulingStatus.Paused)

    UpdateScheduledVideoRequest.updateScheduledVideoRequestValidator[IO]
      .validate(request)
      .map { result =>
        result mustBe request
      }
  }

  it should "reject Active status" in {
    val request = UpdateScheduledVideoRequest(SchedulingStatus.Active)

    val result = UpdateScheduledVideoRequest.updateScheduledVideoRequestValidator[IO]
      .validate(request)
      .attempt
      .unsafeRunSync()

    result.isLeft mustBe true
    result.left.toOption.get mustBe a[ValidationException]
    result.left.toOption.get.getMessage must include("Invalid status value")
  }

  it should "reject Completed status" in {
    val request = UpdateScheduledVideoRequest(SchedulingStatus.Completed)

    val result = UpdateScheduledVideoRequest.updateScheduledVideoRequestValidator[IO]
      .validate(request)
      .attempt
      .unsafeRunSync()

    result.isLeft mustBe true
    result.left.toOption.get mustBe a[ValidationException]
  }

  it should "reject Downloaded status" in {
    val request = UpdateScheduledVideoRequest(SchedulingStatus.Downloaded)

    val result = UpdateScheduledVideoRequest.updateScheduledVideoRequestValidator[IO]
      .validate(request)
      .attempt
      .unsafeRunSync()

    result.isLeft mustBe true
    result.left.toOption.get mustBe a[ValidationException]
  }

  it should "reject Error status" in {
    val request = UpdateScheduledVideoRequest(SchedulingStatus.Error)

    val result = UpdateScheduledVideoRequest.updateScheduledVideoRequestValidator[IO]
      .validate(request)
      .attempt
      .unsafeRunSync()

    result.isLeft mustBe true
    result.left.toOption.get mustBe a[ValidationException]
  }

  "noValidator" should "accept any value" in runIO {
    val anyValue = "test value"

    Validator.noValidator[IO]
      .validate(anyValue)
      .map { result =>
        result mustBe anyValue
      }
  }

  it should "accept case class values" in runIO {
    case class TestClass(value: Int)
    val testInstance = TestClass(42)

    Validator.noValidator[IO]
      .validate(testInstance)
      .map { result =>
        result mustBe testInstance
      }
  }
}
