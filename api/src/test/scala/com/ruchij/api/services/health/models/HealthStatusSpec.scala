package com.ruchij.api.services.health.models

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class HealthStatusSpec extends AnyFlatSpec with Matchers {

  "HealthStatus.Healthy + Healthy" should "return Healthy" in {
    (HealthStatus.Healthy + HealthStatus.Healthy) mustBe HealthStatus.Healthy
  }

  "HealthStatus.Healthy + Unhealthy" should "return Unhealthy" in {
    (HealthStatus.Healthy + HealthStatus.Unhealthy) mustBe HealthStatus.Unhealthy
  }

  "HealthStatus.Unhealthy + Healthy" should "return Unhealthy" in {
    (HealthStatus.Unhealthy + HealthStatus.Healthy) mustBe HealthStatus.Unhealthy
  }

  "HealthStatus.Unhealthy + Unhealthy" should "return Unhealthy" in {
    (HealthStatus.Unhealthy + HealthStatus.Unhealthy) mustBe HealthStatus.Unhealthy
  }

  "HealthStatus.values" should "contain both Healthy and Unhealthy" in {
    HealthStatus.values must contain allOf (HealthStatus.Healthy, HealthStatus.Unhealthy)
    HealthStatus.values.size mustBe 2
  }

  "HealthStatus" should "be an EnumEntry" in {
    HealthStatus.Healthy.entryName mustBe "Healthy"
    HealthStatus.Unhealthy.entryName mustBe "Unhealthy"
  }

  it should "support withName lookup" in {
    HealthStatus.withName("Healthy") mustBe HealthStatus.Healthy
    HealthStatus.withName("Unhealthy") mustBe HealthStatus.Unhealthy
  }

  it should "support withNameOption lookup" in {
    HealthStatus.withNameOption("Healthy") mustBe Some(HealthStatus.Healthy)
    HealthStatus.withNameOption("Invalid") mustBe None
  }

  "Chained + operations" should "propagate Unhealthy" in {
    val result = HealthStatus.Healthy + HealthStatus.Healthy + HealthStatus.Unhealthy + HealthStatus.Healthy
    result mustBe HealthStatus.Unhealthy
  }

  it should "remain Healthy when all Healthy" in {
    val result = HealthStatus.Healthy + HealthStatus.Healthy + HealthStatus.Healthy
    result mustBe HealthStatus.Healthy
  }
}
