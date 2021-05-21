package com.ruchij.api.web.routes

import cats.effect.{IO, Timer}
import com.eed3si9n.ruchij.api.BuildInfo
import com.ruchij.api.test.HttpTestResource
import com.ruchij.api.test.matchers._
import com.ruchij.core.test.Providers._
import com.ruchij.core.circe.Encoders.dateTimeEncoder
import com.ruchij.core.test.IOSupport.runIO
import io.circe.literal._
import org.http4s.Status
import org.http4s.client.dsl.io._
import org.http4s.dsl.io.GET
import org.http4s.implicits.http4sLiteralsSyntax
import org.joda.time.DateTime
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Properties

class ServiceRoutesSpec extends AnyFlatSpec with Matchers {

  "GET /service/info" should "return a successful response containing service information" in {
    val dateTime = DateTime.now()
    implicit val timer: Timer[IO] = stubTimer(dateTime)

    val expectedJsonResponse =
      json"""{
        "serviceName": "video-downloader-api",
        "serviceVersion": ${BuildInfo.version},
        "organization": "com.ruchij",
        "scalaVersion": "2.13.6",
        "sbtVersion": "1.5.2",
        "javaVersion": ${Properties.javaVersion},
        "currentTimestamp": $dateTime,
        "instanceId": "localhost",
        "gitBranch": "N/A",
        "gitCommit": "N/A",
        "buildTimestamp": null
      }"""

    runIO {
      HttpTestResource.create[IO].use {
        case (_, _, application) =>
          for {
            request <- GET(uri"/service/info")
            response <- application.run(request)

            _ = {
              response must beJsonContentType
              response must haveJson(expectedJsonResponse)
              response must haveStatus(Status.Ok)
            }
          }
          yield (): Unit
      }
    }
  }

  "GET /service/health" should "return a health check response" in runIO {
    HttpTestResource.create[IO].use {
      case (_, _, application) =>
        for {
          request <- GET(uri"/service/health")
          response <- application.run(request)

          _ = {
            response must beJsonContentType
            response must haveStatus(Status.Ok)
            response must haveJson {
              json"""{
                "database" : "Healthy",
                "fileRepository" : "Healthy",
                "keyValueStore" : "Healthy",
                "pubSubStatus" : "Healthy"
              }"""
            }
          }
        }
        yield (): Unit
    }
  }
}
