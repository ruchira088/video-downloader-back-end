package com.ruchij.core.daos.workers.models

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class WorkerStatusSpec extends AnyFlatSpec with Matchers {

  "WorkerStatus.withNameInsensitive" should "parse all valid status values" in {
    WorkerStatus.withNameInsensitive("Active") mustBe WorkerStatus.Active
    WorkerStatus.withNameInsensitive("Reserved") mustBe WorkerStatus.Reserved
    WorkerStatus.withNameInsensitive("Available") mustBe WorkerStatus.Available
    WorkerStatus.withNameInsensitive("Paused") mustBe WorkerStatus.Paused
    WorkerStatus.withNameInsensitive("Deleted") mustBe WorkerStatus.Deleted
  }

  it should "be case insensitive" in {
    WorkerStatus.withNameInsensitive("active") mustBe WorkerStatus.Active
    WorkerStatus.withNameInsensitive("ACTIVE") mustBe WorkerStatus.Active
    WorkerStatus.withNameInsensitive("paused") mustBe WorkerStatus.Paused
    WorkerStatus.withNameInsensitive("PAUSED") mustBe WorkerStatus.Paused
  }

  "WorkerStatus.withNameOption" should "return None for invalid input" in {
    WorkerStatus.withNameOption("invalid") mustBe None
    WorkerStatus.withNameOption("") mustBe None
    WorkerStatus.withNameOption("running") mustBe None
    WorkerStatus.withNameOption("idle") mustBe None
  }

  "WorkerStatus.values" should "contain all status types" in {
    WorkerStatus.values must contain allOf (
      WorkerStatus.Active,
      WorkerStatus.Reserved,
      WorkerStatus.Available,
      WorkerStatus.Paused,
      WorkerStatus.Deleted
    )
    WorkerStatus.values.size mustBe 5
  }

  "entryName" should "return the correct string representation" in {
    WorkerStatus.Active.entryName mustBe "Active"
    WorkerStatus.Reserved.entryName mustBe "Reserved"
    WorkerStatus.Available.entryName mustBe "Available"
    WorkerStatus.Paused.entryName mustBe "Paused"
    WorkerStatus.Deleted.entryName mustBe "Deleted"
  }
}
