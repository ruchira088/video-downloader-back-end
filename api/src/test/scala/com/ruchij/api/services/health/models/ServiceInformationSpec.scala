package com.ruchij.api.services.health.models

import cats.effect.IO
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.test.Providers
import com.ruchij.core.types.{Clock, TimeUtils}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class ServiceInformationSpec extends AnyFlatSpec with Matchers {

  private val timestamp = TimeUtils.instantOf(2024, 5, 15, 10, 30)

  "ServiceInformation.create" should "create ServiceInformation with provided yt-dlp version" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    ServiceInformation.create[IO]("2024.05.01").map { info =>
      info.`yt-dlpVersion` mustBe "2024.05.01"
      info.currentTimestamp mustBe timestamp
      info.serviceName must not be empty
      info.organization must not be empty
      info.scalaVersion must not be empty
      info.sbtVersion must not be empty
      info.javaVersion must not be empty
    }
  }

  it should "use current timestamp from Clock" in runIO {
    val customTimestamp = TimeUtils.instantOf(2025, 1, 15, 12, 0)
    implicit val clock: Clock[IO] = Providers.stubClock[IO](customTimestamp)

    ServiceInformation.create[IO]("2024.05.01").map { info =>
      info.currentTimestamp mustBe customTimestamp
    }
  }

  it should "fetch Java version from system properties" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    ServiceInformation.create[IO]("2024.05.01").map { info =>
      info.javaVersion must not be empty
    }
  }

  "ServiceInformation case class" should "store all provided fields" in {
    val buildTimestamp = TimeUtils.instantOf(2024, 1, 1, 0, 0)

    val info = ServiceInformation(
      serviceName = "test-service",
      organization = "test-org",
      scalaVersion = "2.13.12",
      sbtVersion = "1.9.0",
      javaVersion = "17.0.1",
      `yt-dlpVersion` = "2024.05.01",
      currentTimestamp = timestamp,
      gitBranch = Some("main"),
      gitCommit = Some("abc123"),
      buildTimestamp = buildTimestamp
    )

    info.serviceName mustBe "test-service"
    info.organization mustBe "test-org"
    info.scalaVersion mustBe "2.13.12"
    info.sbtVersion mustBe "1.9.0"
    info.javaVersion mustBe "17.0.1"
    info.`yt-dlpVersion` mustBe "2024.05.01"
    info.currentTimestamp mustBe timestamp
    info.gitBranch mustBe Some("main")
    info.gitCommit mustBe Some("abc123")
    info.buildTimestamp mustBe buildTimestamp
  }

  it should "allow empty git branch and commit" in {
    val buildTimestamp = TimeUtils.instantOf(2024, 1, 1, 0, 0)

    val info = ServiceInformation(
      serviceName = "test-service",
      organization = "test-org",
      scalaVersion = "2.13.12",
      sbtVersion = "1.9.0",
      javaVersion = "17.0.1",
      `yt-dlpVersion` = "2024.05.01",
      currentTimestamp = timestamp,
      gitBranch = None,
      gitCommit = None,
      buildTimestamp = buildTimestamp
    )

    info.gitBranch mustBe None
    info.gitCommit mustBe None
  }
}
