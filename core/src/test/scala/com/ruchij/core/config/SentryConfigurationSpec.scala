package com.ruchij.core.config

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class SentryConfigurationSpec extends AnyFlatSpec with Matchers {

  "SentryConfiguration" should "hold DSN, environment, and traces sample rate" in {
    val config = SentryConfiguration(
      dsn = Some("https://key@sentry.io/123"),
      environment = "production",
      tracesSampleRate = 0.5
    )

    config.dsn mustBe Some("https://key@sentry.io/123")
    config.environment mustBe "production"
    config.tracesSampleRate mustBe 0.5
  }

  it should "support None DSN for disabled Sentry" in {
    val config = SentryConfiguration(
      dsn = None,
      environment = "development",
      tracesSampleRate = 1.0
    )

    config.dsn mustBe None
  }

  it should "support equality comparison" in {
    val config1 = SentryConfiguration(Some("dsn"), "prod", 0.5)
    val config2 = SentryConfiguration(Some("dsn"), "prod", 0.5)
    val config3 = SentryConfiguration(Some("dsn"), "staging", 0.5)

    config1 mustBe config2
    config1 must not be config3
  }

  it should "support copy" in {
    val config = SentryConfiguration(Some("dsn"), "prod", 0.5)
    val updated = config.copy(environment = "staging")

    updated.dsn mustBe Some("dsn")
    updated.environment mustBe "staging"
    updated.tracesSampleRate mustBe 0.5
  }
}
