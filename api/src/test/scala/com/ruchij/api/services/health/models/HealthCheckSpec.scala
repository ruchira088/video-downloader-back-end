package com.ruchij.api.services.health.models

import com.ruchij.api.services.health.models.HealthCheck.{FilePathCheck, FileRepositoryCheck, HealthStatusDetails}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class HealthCheckSpec extends AnyFlatSpec with Matchers {

  private val healthyDetails = HealthStatusDetails(100L, HealthStatus.Healthy)
  private val unhealthyDetails = HealthStatusDetails(200L, HealthStatus.Unhealthy)

  private def healthyFilePathCheck(path: String) = FilePathCheck(path, healthyDetails)
  private def unhealthyFilePathCheck(path: String) = FilePathCheck(path, unhealthyDetails)

  "HealthCheck.isHealthy" should "return true when all checks are healthy" in {
    val healthCheck = HealthCheck(
      database = healthyDetails,
      fileRepository = FileRepositoryCheck(
        healthyFilePathCheck("/images"),
        healthyFilePathCheck("/videos"),
        List.empty
      ),
      keyValueStore = healthyDetails,
      pubSub = healthyDetails,
      spaRenderer = healthyDetails,
      internetConnectivity = healthyDetails
    )

    healthCheck.isHealthy mustBe true
  }

  it should "return false when database is unhealthy" in {
    val healthCheck = HealthCheck(
      database = unhealthyDetails,
      fileRepository = FileRepositoryCheck(
        healthyFilePathCheck("/images"),
        healthyFilePathCheck("/videos"),
        List.empty
      ),
      keyValueStore = healthyDetails,
      pubSub = healthyDetails,
      spaRenderer = healthyDetails,
      internetConnectivity = healthyDetails
    )

    healthCheck.isHealthy mustBe false
  }

  it should "return false when keyValueStore is unhealthy" in {
    val healthCheck = HealthCheck(
      database = healthyDetails,
      fileRepository = FileRepositoryCheck(
        healthyFilePathCheck("/images"),
        healthyFilePathCheck("/videos"),
        List.empty
      ),
      keyValueStore = unhealthyDetails,
      pubSub = healthyDetails,
      spaRenderer = healthyDetails,
      internetConnectivity = healthyDetails
    )

    healthCheck.isHealthy mustBe false
  }

  it should "return false when pubSub is unhealthy" in {
    val healthCheck = HealthCheck(
      database = healthyDetails,
      fileRepository = FileRepositoryCheck(
        healthyFilePathCheck("/images"),
        healthyFilePathCheck("/videos"),
        List.empty
      ),
      keyValueStore = healthyDetails,
      pubSub = unhealthyDetails,
      spaRenderer = healthyDetails,
      internetConnectivity = healthyDetails
    )

    healthCheck.isHealthy mustBe false
  }

  it should "return false when spaRenderer is unhealthy" in {
    val healthCheck = HealthCheck(
      database = healthyDetails,
      fileRepository = FileRepositoryCheck(
        healthyFilePathCheck("/images"),
        healthyFilePathCheck("/videos"),
        List.empty
      ),
      keyValueStore = healthyDetails,
      pubSub = healthyDetails,
      spaRenderer = unhealthyDetails,
      internetConnectivity = healthyDetails
    )

    healthCheck.isHealthy mustBe false
  }

  it should "return false when internetConnectivity is unhealthy" in {
    val healthCheck = HealthCheck(
      database = healthyDetails,
      fileRepository = FileRepositoryCheck(
        healthyFilePathCheck("/images"),
        healthyFilePathCheck("/videos"),
        List.empty
      ),
      keyValueStore = healthyDetails,
      pubSub = healthyDetails,
      spaRenderer = healthyDetails,
      internetConnectivity = unhealthyDetails
    )

    healthCheck.isHealthy mustBe false
  }

  it should "return false when image folder is unhealthy" in {
    val healthCheck = HealthCheck(
      database = healthyDetails,
      fileRepository = FileRepositoryCheck(
        unhealthyFilePathCheck("/images"),
        healthyFilePathCheck("/videos"),
        List.empty
      ),
      keyValueStore = healthyDetails,
      pubSub = healthyDetails,
      spaRenderer = healthyDetails,
      internetConnectivity = healthyDetails
    )

    healthCheck.isHealthy mustBe false
  }

  it should "return false when video folder is unhealthy" in {
    val healthCheck = HealthCheck(
      database = healthyDetails,
      fileRepository = FileRepositoryCheck(
        healthyFilePathCheck("/images"),
        unhealthyFilePathCheck("/videos"),
        List.empty
      ),
      keyValueStore = healthyDetails,
      pubSub = healthyDetails,
      spaRenderer = healthyDetails,
      internetConnectivity = healthyDetails
    )

    healthCheck.isHealthy mustBe false
  }

  it should "return false when any other video folder is unhealthy" in {
    val healthCheck = HealthCheck(
      database = healthyDetails,
      fileRepository = FileRepositoryCheck(
        healthyFilePathCheck("/images"),
        healthyFilePathCheck("/videos"),
        List(
          healthyFilePathCheck("/other1"),
          unhealthyFilePathCheck("/other2"),
          healthyFilePathCheck("/other3")
        )
      ),
      keyValueStore = healthyDetails,
      pubSub = healthyDetails,
      spaRenderer = healthyDetails,
      internetConnectivity = healthyDetails
    )

    healthCheck.isHealthy mustBe false
  }

  it should "return true with healthy other video folders" in {
    val healthCheck = HealthCheck(
      database = healthyDetails,
      fileRepository = FileRepositoryCheck(
        healthyFilePathCheck("/images"),
        healthyFilePathCheck("/videos"),
        List(
          healthyFilePathCheck("/other1"),
          healthyFilePathCheck("/other2")
        )
      ),
      keyValueStore = healthyDetails,
      pubSub = healthyDetails,
      spaRenderer = healthyDetails,
      internetConnectivity = healthyDetails
    )

    healthCheck.isHealthy mustBe true
  }

  "FileRepositoryCheck.isHealthy" should "return true when all paths are healthy" in {
    val fileRepositoryCheck = FileRepositoryCheck(
      healthyFilePathCheck("/images"),
      healthyFilePathCheck("/videos"),
      List(healthyFilePathCheck("/other1"), healthyFilePathCheck("/other2"))
    )

    fileRepositoryCheck.isHealthy mustBe true
  }

  it should "return false when image folder is unhealthy" in {
    val fileRepositoryCheck = FileRepositoryCheck(
      unhealthyFilePathCheck("/images"),
      healthyFilePathCheck("/videos"),
      List.empty
    )

    fileRepositoryCheck.isHealthy mustBe false
  }

  it should "return false when video folder is unhealthy" in {
    val fileRepositoryCheck = FileRepositoryCheck(
      healthyFilePathCheck("/images"),
      unhealthyFilePathCheck("/videos"),
      List.empty
    )

    fileRepositoryCheck.isHealthy mustBe false
  }

  it should "return false when any other folder is unhealthy" in {
    val fileRepositoryCheck = FileRepositoryCheck(
      healthyFilePathCheck("/images"),
      healthyFilePathCheck("/videos"),
      List(healthyFilePathCheck("/other1"), unhealthyFilePathCheck("/other2"))
    )

    fileRepositoryCheck.isHealthy mustBe false
  }

  "HealthStatusDetails" should "store duration and health status" in {
    val details = HealthStatusDetails(150L, HealthStatus.Healthy)

    details.durationInMs mustBe 150L
    details.healthStatus mustBe HealthStatus.Healthy
  }

  "FilePathCheck" should "store file path and health status details" in {
    val check = FilePathCheck("/data/videos", healthyDetails)

    check.filePath mustBe "/data/videos"
    check.healthStatusDetails mustBe healthyDetails
  }
}
