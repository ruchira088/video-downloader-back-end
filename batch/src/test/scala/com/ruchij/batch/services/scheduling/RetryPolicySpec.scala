package com.ruchij.batch.services.scheduling

import com.ruchij.batch.config.RetryConfiguration
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.duration._

class RetryPolicySpec extends AnyFlatSpec with Matchers with OptionValues {
  private val configuration = RetryConfiguration(maxAttempts = 5, baseDelay = 1.minute, maxDelay = 30.minutes)
  private val retryPolicy = RetryPolicy(configuration)
  private val jitters = List(0.0, 0.25, 0.5, 0.75, 0.999999)

  // The largest delay any strategy may pick for an attempt: base * 2^attempt, capped at maxDelay
  private def ceiling(attempt: Int): FiniteDuration =
    (configuration.baseDelay * math.pow(2, attempt).toLong).min(configuration.maxDelay)

  "nextDelay" should "give up once the attempt limit is reached" in {
    retryPolicy.nextDelay(5, 0.5) mustBe None
    retryPolicy.nextDelay(6, 0.5) mustBe None
  }

  it should "never retry when maxAttempts is 0" in {
    RetryPolicy(RetryConfiguration.Disabled).nextDelay(0, 0.5) mustBe None
  }

  it should "keep every delay between zero and the backoff ceiling for its attempt" in {
    for {
      attempt <- 0 until configuration.maxAttempts
      jitter <- jitters
    } {
      val delay = retryPolicy.nextDelay(attempt, jitter).value
      delay must be >= Duration.Zero
      delay must be <= ceiling(attempt)
    }
  }

  it should "let later attempts wait longer at the same jitter" in {
    for (jitter <- jitters.filter(_ > 0)) {
      retryPolicy.nextDelay(3, jitter).value must be > retryPolicy.nextDelay(0, jitter).value
    }
  }

  it should "cap delays at maxDelay" in {
    val capped = RetryPolicy(configuration.copy(maxAttempts = 20))

    for (jitter <- jitters) {
      capped.nextDelay(15, jitter).value must be <= configuration.maxDelay
    }
  }

  // base * 2^attempt overflows a Long well before attempt 100
  it should "never overflow for large attempt numbers" in {
    val many = RetryPolicy(configuration.copy(maxAttempts = Int.MaxValue))

    for {
      attempt <- List(62, 63, 64, 100, 1_000, Int.MaxValue - 1)
      jitter <- jitters
    } {
      val delay = many.nextDelay(attempt, jitter).value
      delay must be >= Duration.Zero
      delay must be <= configuration.maxDelay
    }
  }

  it should "spread retries that fail at the same moment" in {
    retryPolicy.nextDelay(2, 0.1).value must not equal retryPolicy.nextDelay(2, 0.9).value
  }

  it should "be deterministic for the same attempt and jitter" in {
    retryPolicy.nextDelay(2, 0.42) mustBe retryPolicy.nextDelay(2, 0.42)
  }
}
