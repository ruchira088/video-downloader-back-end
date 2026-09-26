package com.ruchij.api.config

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import com.typesafe.config.ConfigFactory
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
          None,
          reconcileAllowMassRemoval = false
        )
      )
    )
  }

  it should "read the mass-removal override" in {
    val configuration =
      ConfigSource
        .string(
          """
            enabled = true
            main-to-fallback-queue-url = "https://sqs.example.com/m2f"
            fallback-to-main-queue-url = "https://sqs.example.com/f2m"
            table-name = "prod-fallback-scheduled-videos"
            aws-region = "ap-southeast-2"
            reconcile-allow-mass-removal = true
          """
        )
        .loadOrThrow[FallbackSyncConfiguration]

    configuration.settings.map(_.map(_.reconcileAllowMassRemoval)) mustBe Right(Some(true))
  }

  it should "read the mass-removal override from FALLBACK_SYNC_RECONCILE_ALLOW_MASS_REMOVAL in application.conf" in {
    // Only this block is resolved: the rest of application.conf needs variables the test doesn't set
    val block = ConfigFactory.parseResources("application.conf").withOnlyPath("fallback-sync-configuration")

    def allowMassRemoval(variables: String): Boolean =
      ConfigSource
        .fromConfig(block.withFallback(ConfigFactory.parseString(variables)).resolve())
        .at("fallback-sync-configuration")
        .loadOrThrow[FallbackSyncConfiguration]
        .reconcileAllowMassRemoval

    allowMassRemoval("FALLBACK_SYNC_RECONCILE_ALLOW_MASS_REMOVAL = true") mustBe true
    allowMassRemoval("") mustBe false
  }

  it should "produce no settings when disabled, even with nothing else set" in {
    ConfigSource.string("enabled = false").loadOrThrow[FallbackSyncConfiguration].settings mustBe Right(None)
  }

  it should "fail when enabled without every required setting" in {
    FallbackSyncConfiguration.Disabled.copy(enabled = true, tableName = Some("t")).settings.isLeft mustBe true
  }
}
