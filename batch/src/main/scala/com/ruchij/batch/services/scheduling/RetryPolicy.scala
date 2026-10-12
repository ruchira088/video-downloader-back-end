package com.ruchij.batch.services.scheduling

import com.ruchij.batch.config.RetryConfiguration

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

final case class RetryPolicy(configuration: RetryConfiguration) {

  /**
    * The delay before retrying a failed download, or None to give up and leave it in Error.
    *
    * @param attempt how many failures of this download have already been retried (its current retryCount)
    * @param jitter  a uniform random value in [0, 1), drawn by the caller so this function stays deterministic
    */
  def nextDelay(attempt: Int, jitter: Double): Option[FiniteDuration] =
    if (attempt < 0 || attempt >= configuration.maxAttempts) None
    else {
      // Equal jitter: wait at least half the backoff, and a random share of the other half. Math on Double turns
      // 2^attempt into Infinity rather than overflowing, and min then caps it at maxDelay.
      val backoffMillis =
        math.min(configuration.baseDelay.toMillis * math.pow(2, attempt), configuration.maxDelay.toMillis.toDouble)
      val halfMillis = backoffMillis / 2

      Some(FiniteDuration((halfMillis + halfMillis * jitter).toLong, TimeUnit.MILLISECONDS))
    }
}
