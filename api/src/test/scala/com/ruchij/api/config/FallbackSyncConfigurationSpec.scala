package com.ruchij.api.config

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import pureconfig.ConfigSource
import pureconfig.generic.auto._

class FallbackSyncConfigurationSpec extends AnyFlatSpec with Matchers {

  "FallbackSyncConfiguration" should "parse a fully configured block into settings" in {
    val configuration =
      ConfigSource
        .string(
          """
            enabled = true
            main-to-fallback-queue-url = "https://sqs.example.com/m2f"
            fallback-to-main-queue-url = "https://sqs.example.com/f2m"
            table-name = "prod-fallback-scheduled-videos"
            aws-region = "ap-southeast-2"
          """
        )
        .loadOrThrow[FallbackSyncConfiguration]

    configuration.settings mustBe Right(
      Some(
        FallbackSyncSettings(
          "https://sqs.example.com/m2f",
          "https://sqs.example.com/f2m",
          "prod-fallback-scheduled-videos",
          "ap-southeast-2",
          None
        )
      )
    )
  }

  it should "produce no settings when disabled, even with nothing else set" in {
    ConfigSource.string("enabled = false").loadOrThrow[FallbackSyncConfiguration].settings mustBe Right(None)
  }

  it should "fail when enabled without every required setting" in {
    FallbackSyncConfiguration.Disabled.copy(enabled = true, tableName = Some("t")).settings.isLeft mustBe true
  }
}
