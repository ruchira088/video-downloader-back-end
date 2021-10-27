package com.ruchij.api.web.routes

import cats.effect.IO
import com.ruchij.api.services.health.models.{HealthCheck, HealthStatus, ServiceInformation}
import com.ruchij.api.test.matchers._
import com.ruchij.api.test.mixins.io.MockedRoutesIO
import com.ruchij.core.test.IOSupport.runIO
import io.circe.literal._
import org.http4s.Status
import org.http4s.client.dsl.io._
import org.http4s.dsl.io.GET
import org.http4s.implicits.http4sLiteralsSyntax
import org.joda.time.{DateTime, DateTimeZone}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class ServiceRoutesSpec extends AnyFlatSpec with Matchers with MockedRoutesIO {

  "GET /service/info" should "return a successful response containing service information" in runIO {
    val expectedJsonResponse =
      json"""{
        "serviceName": "video-downloader-api",
        "serviceVersion": "1.0.0",
        "organization": "com.ruchij",
        "scalaVersion": "2.13.6",
        "sbtVersion": "1.5.5",
        "javaVersion": "1.8.0_302",
        "currentTimestamp": "2021-08-01T10:10:00.000Z",
        "instanceId": "localhost",
        "gitBranch": "my-branch",
        "gitCommit": "my-commit",
        "buildTimestamp": null
      }"""

    (() => healthService.serviceInformation).expects()
      .returns {
        IO.pure {
          ServiceInformation(
            "video-downloader-api",
            "1.0.0",
            "com.ruchij",
            "2.13.6",
            "1.5.5",
            "1.8.0_302",
            new DateTime(2021, 8, 1, 10, 10, 0, 0, DateTimeZone.UTC),
            "localhost",
            Some("my-branch"),
            Some("my-commit"),
            None
          )
        }
      }

    ignoreHttpMetrics() *>
      createRoutes().run(GET(uri"/service/info"))
        .flatMap { response =>
          IO.delay {
            response must beJsonContentType
            response must haveJson(expectedJsonResponse)
            response must haveStatus(Status.Ok)
          }
        }
  }

  "GET /service/health" should "return a 200 status health check response when all health checks are healthy" in runIO {
    val expectedJsonResponse =
      json"""{
        "database" : "Healthy",
        "fileRepository" : "Healthy",
        "keyValueStore" : "Healthy",
        "pubSubStatus" : "Healthy"
      }"""

    (() => healthService.healthCheck).expects()
      .returns {
        IO.pure {
          HealthCheck(HealthStatus.Healthy, HealthStatus.Healthy, HealthStatus.Healthy, HealthStatus.Healthy)
        }
      }

    ignoreHttpMetrics() *>
      createRoutes().run(GET(uri"/service/health"))
        .flatMap { response =>
          IO.delay {
            response must beJsonContentType
            response must haveJson(expectedJsonResponse)
            response must haveStatus(Status.Ok)
          }
        }
  }

  it should "return a 503 status health check response when at least one of the health checks are unhealthy" in runIO {
    val expectedJsonResponse =
      json"""{
        "database" : "Healthy",
        "fileRepository" : "Unhealthy",
        "keyValueStore" : "Healthy",
        "pubSubStatus" : "Healthy"
      }"""

    (() => healthService.healthCheck).expects()
      .returns {
        IO.pure {
          HealthCheck(HealthStatus.Healthy, HealthStatus.Unhealthy, HealthStatus.Healthy, HealthStatus.Healthy)
        }
      }

    ignoreHttpMetrics() *>
      createRoutes().run(GET(uri"/service/health"))
        .flatMap { response =>
          IO.delay {
            response must beJsonContentType
            response must haveStatus(Status.ServiceUnavailable)
            response must haveJson(expectedJsonResponse)
          }
        }
  }
}
