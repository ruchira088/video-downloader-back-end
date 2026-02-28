package com.ruchij.core.monitoring

import cats.effect.IO
import com.ruchij.core.config.SentryConfiguration
import com.ruchij.core.test.IOSupport.runIO
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class SentrySpec extends AnyFlatSpec with Matchers {

  "init" should "succeed when DSN is None (Sentry disabled)" in runIO {
    val config = SentryConfiguration(dsn = None, environment = "test", tracesSampleRate = 0.0)

    Sentry.init[IO](config).use { _ =>
      IO.unit
    }
  }

  it should "succeed when DSN is empty string (Sentry disabled)" in runIO {
    val config = SentryConfiguration(dsn = Some(""), environment = "test", tracesSampleRate = 0.0)

    Sentry.init[IO](config).use { _ =>
      IO.unit
    }
  }

  it should "succeed when DSN is whitespace only (Sentry disabled)" in runIO {
    val config = SentryConfiguration(dsn = Some("   "), environment = "test", tracesSampleRate = 0.0)

    Sentry.init[IO](config).use { _ =>
      IO.unit
    }
  }

  "captureException" should "not throw when called" in runIO {
    val exception = new RuntimeException("test error")
    Sentry.captureException[IO](exception)
  }

  "captureMessage" should "not throw when called" in runIO {
    Sentry.captureMessage[IO]("test message")
  }

  "setTag" should "not throw when called" in runIO {
    Sentry.setTag[IO]("key", "value")
  }

  "setExtra" should "not throw when called" in runIO {
    Sentry.setExtra[IO]("key", "value")
  }
}
