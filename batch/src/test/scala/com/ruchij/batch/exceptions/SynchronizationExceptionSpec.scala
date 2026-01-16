package com.ruchij.batch.exceptions

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class SynchronizationExceptionSpec extends AnyFlatSpec with Matchers {

  "SynchronizationException" should "extend Exception" in {
    val exception = SynchronizationException("test message")
    exception mustBe an[Exception]
  }

  it should "store the message" in {
    val message = "Failed to synchronize video file"
    val exception = SynchronizationException(message)
    exception.getMessage mustBe message
  }

  it should "be a case class" in {
    val exception1 = SynchronizationException("message1")
    val exception2 = SynchronizationException("message1")
    exception1 mustBe exception2
  }

  it should "have different instances for different messages" in {
    val exception1 = SynchronizationException("message1")
    val exception2 = SynchronizationException("message2")
    exception1 must not be exception2
  }

  it should "expose message through case class accessor" in {
    val message = "Sync failed"
    val exception = SynchronizationException(message)
    exception.message mustBe message
  }

  it should "be catchable as Throwable" in {
    def throwException(): Unit = throw SynchronizationException("test")

    val caught = intercept[Throwable] {
      throwException()
    }
    caught mustBe a[SynchronizationException]
  }
}
