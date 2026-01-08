package com.ruchij.core.daos.scheduling.models

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class SchedulingStatusSpec extends AnyFlatSpec with Matchers {

  "SchedulingStatus.withNameInsensitive" should "parse all valid status values" in {
    SchedulingStatus.withNameInsensitive("Active") mustBe SchedulingStatus.Active
    SchedulingStatus.withNameInsensitive("Completed") mustBe SchedulingStatus.Completed
    SchedulingStatus.withNameInsensitive("Downloaded") mustBe SchedulingStatus.Downloaded
    SchedulingStatus.withNameInsensitive("Acquired") mustBe SchedulingStatus.Acquired
    SchedulingStatus.withNameInsensitive("Stale") mustBe SchedulingStatus.Stale
    SchedulingStatus.withNameInsensitive("Error") mustBe SchedulingStatus.Error
    SchedulingStatus.withNameInsensitive("WorkersPaused") mustBe SchedulingStatus.WorkersPaused
    SchedulingStatus.withNameInsensitive("Paused") mustBe SchedulingStatus.Paused
    SchedulingStatus.withNameInsensitive("Queued") mustBe SchedulingStatus.Queued
    SchedulingStatus.withNameInsensitive("Deleted") mustBe SchedulingStatus.Deleted
  }

  it should "be case insensitive" in {
    SchedulingStatus.withNameInsensitive("active") mustBe SchedulingStatus.Active
    SchedulingStatus.withNameInsensitive("ACTIVE") mustBe SchedulingStatus.Active
    SchedulingStatus.withNameInsensitive("completed") mustBe SchedulingStatus.Completed
    SchedulingStatus.withNameInsensitive("COMPLETED") mustBe SchedulingStatus.Completed
  }

  "SchedulingStatus.withNameOption" should "return None for invalid input" in {
    SchedulingStatus.withNameOption("invalid") mustBe None
    SchedulingStatus.withNameOption("") mustBe None
    SchedulingStatus.withNameOption("running") mustBe None
  }

  "SchedulingStatus.values" should "contain all status types" in {
    SchedulingStatus.values must contain allOf (
      SchedulingStatus.Active,
      SchedulingStatus.Completed,
      SchedulingStatus.Downloaded,
      SchedulingStatus.Acquired,
      SchedulingStatus.Stale,
      SchedulingStatus.Error,
      SchedulingStatus.WorkersPaused,
      SchedulingStatus.Paused,
      SchedulingStatus.Queued,
      SchedulingStatus.Deleted
    )
    SchedulingStatus.values.size mustBe 10
  }

  "entryName" should "return the correct string representation" in {
    SchedulingStatus.Active.entryName mustBe "Active"
    SchedulingStatus.Completed.entryName mustBe "Completed"
    SchedulingStatus.Downloaded.entryName mustBe "Downloaded"
    SchedulingStatus.Acquired.entryName mustBe "Acquired"
    SchedulingStatus.Stale.entryName mustBe "Stale"
    SchedulingStatus.Error.entryName mustBe "Error"
    SchedulingStatus.WorkersPaused.entryName mustBe "WorkersPaused"
    SchedulingStatus.Paused.entryName mustBe "Paused"
    SchedulingStatus.Queued.entryName mustBe "Queued"
    SchedulingStatus.Deleted.entryName mustBe "Deleted"
  }
}
