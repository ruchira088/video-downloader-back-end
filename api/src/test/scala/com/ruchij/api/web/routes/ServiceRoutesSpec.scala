package com.ruchij.api.web.routes

import cats.effect.{Clock, IO}
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
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.util.concurrent.TimeUnit

class ServiceRoutesSpec extends AnyFlatSpec with Matchers with MockFactory with MockedRoutesIO {

  "GET /service/info" should "return a successful response containing service information" in runIO {
    val dateTime = new DateTime(2021, 8, 1, 10, 10, 0, 0, DateTimeZone.UTC)

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

    val clock = mock[Clock[IO]]
    (clock.realTime _).expects(TimeUnit.MILLISECONDS).returns(IO.pure(dateTime.getMillis)).repeat(2)

    (() => timer.clock).expects().returns(clock)

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
            dateTime,
            "localhost",
            Some("my-branch"),
            Some("my-commit"),
            None
          )
        }
      }

    (metricPublisher.publishOne _).expects(*).returns(IO.unit)

    createRoutes().run(GET(uri"/service/info")).flatMap {
      response => IO.delay {
        response must beJsonContentType
        response must haveJson(expectedJsonResponse)
        response must haveStatus(Status.Ok)
      }
    }
  }

  "GET /service/health" should "return a health check response" in runIO {
    val expectedJsonResponse =
      json"""{
        "database" : "Healthy",
        "fileRepository" : "Healthy",
        "keyValueStore" : "Healthy",
        "pubSubStatus" : "Healthy"
      }"""

    val clock = mock[Clock[IO]]
    (clock.realTime _).expects(TimeUnit.MILLISECONDS).returns(IO.pure(0)).repeat(2)

    (() => timer.clock).expects().returns(clock)

    (() => healthService.healthCheck).expects()
      .returns {
        IO.pure {
          HealthCheck(HealthStatus.Healthy, HealthStatus.Healthy, HealthStatus.Healthy, HealthStatus.Healthy)
        }
      }

    (metricPublisher.publishOne _).expects(*).returns(IO.unit)

    createRoutes().run(GET(uri"/service/health")).flatMap {
      response =>
        IO.delay {
          response must beJsonContentType
          response must haveStatus(Status.Ok)
          response must haveJson(expectedJsonResponse)
        }
    }
  }
}
