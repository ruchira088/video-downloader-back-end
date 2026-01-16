package com.ruchij.batch.daos.workers.models

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class WorkerSpec extends AnyFlatSpec with Matchers {

  "workerIdFromIndex" should "generate worker ID with leading zero for single digit indices" in {
    Worker.workerIdFromIndex(0) mustBe "worker-00"
    Worker.workerIdFromIndex(1) mustBe "worker-01"
    Worker.workerIdFromIndex(5) mustBe "worker-05"
    Worker.workerIdFromIndex(9) mustBe "worker-09"
  }

  it should "generate worker ID without leading zero for double digit indices" in {
    Worker.workerIdFromIndex(10) mustBe "worker-10"
    Worker.workerIdFromIndex(11) mustBe "worker-11"
    Worker.workerIdFromIndex(25) mustBe "worker-25"
    Worker.workerIdFromIndex(99) mustBe "worker-99"
  }

  it should "handle large indices" in {
    Worker.workerIdFromIndex(100) mustBe "worker-100"
    Worker.workerIdFromIndex(1000) mustBe "worker-1000"
  }

  it should "handle negative indices" in {
    // Negative indices pass through the conditional check (< 10) as false since -1 < 10
    Worker.workerIdFromIndex(-1) mustBe "worker-0-1"
  }
}
