package com.ruchij.web.routes

import cats.effect.{Clock, IO}
import com.ruchij.circe.Encoders.dateTimeEncoder
import com.ruchij.test.HttpTestApp
import com.ruchij.test.utils.Providers.stubClock
import com.ruchij.test.matchers._
import io.circe.literal._
import org.http4s.{Request, Status, Uri}
import org.joda.time.DateTime
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.util.Properties

class ServiceRoutesSpec extends AnyFlatSpec with Matchers {
  "GET /service" should "return a successful response containing service information" in {
    val dateTime = DateTime.now()
    implicit val clock: Clock[IO] = stubClock[IO](dateTime)

    val application = HttpTestApp[IO]()

    val request = Request[IO](uri = Uri(path = "/service"))

    val response = application.run(request).unsafeRunSync()

    val expectedJsonResponse =
      json"""{
        "serviceName": "my-http4s-project",
        "serviceVersion": "0.0.1",
        "organization": "com.ruchij",
        "scalaVersion": "2.13.1",
        "sbtVersion": "1.3.8",
        "javaVersion": ${Properties.javaVersion},
        "timestamp": $dateTime
      }"""

    response must beJsonContentType
    response must haveJson(expectedJsonResponse)
    response must haveStatus(Status.Ok)
  }
}
