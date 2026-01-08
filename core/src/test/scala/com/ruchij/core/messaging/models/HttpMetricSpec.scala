package com.ruchij.core.messaging.models

import org.http4s.implicits.http4sLiteralsSyntax
import org.http4s.{MediaType, Method, Status}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.duration._

class HttpMetricSpec extends AnyFlatSpec with Matchers {

  "HttpMetric" should "store all provided values" in {
    val metric = HttpMetric(
      method = Method.GET,
      uri = uri"/api/videos/search",
      finiteDuration = 150.milliseconds,
      responseStatus = Status.Ok,
      contentType = Some(MediaType.application.json),
      size = Some(1024L)
    )

    metric.method mustBe Method.GET
    metric.uri mustBe uri"/api/videos/search"
    metric.finiteDuration mustBe 150.milliseconds
    metric.responseStatus mustBe Status.Ok
    metric.contentType mustBe Some(MediaType.application.json)
    metric.size mustBe Some(1024L)
  }

  it should "handle optional fields as None" in {
    val metric = HttpMetric(
      method = Method.POST,
      uri = uri"/api/schedule",
      finiteDuration = 50.milliseconds,
      responseStatus = Status.Created,
      contentType = None,
      size = None
    )

    metric.contentType mustBe None
    metric.size mustBe None
  }

  it should "handle various HTTP methods" in {
    HttpMetric(Method.GET, uri"/", 1.second, Status.Ok, None, None).method mustBe Method.GET
    HttpMetric(Method.POST, uri"/", 1.second, Status.Created, None, None).method mustBe Method.POST
    HttpMetric(Method.PUT, uri"/", 1.second, Status.Ok, None, None).method mustBe Method.PUT
    HttpMetric(Method.DELETE, uri"/", 1.second, Status.NoContent, None, None).method mustBe Method.DELETE
    HttpMetric(Method.PATCH, uri"/", 1.second, Status.Ok, None, None).method mustBe Method.PATCH
  }

  it should "handle various HTTP statuses" in {
    HttpMetric(Method.GET, uri"/", 1.second, Status.Ok, None, None).responseStatus mustBe Status.Ok
    HttpMetric(Method.GET, uri"/", 1.second, Status.NotFound, None, None).responseStatus mustBe Status.NotFound
    HttpMetric(Method.GET, uri"/", 1.second, Status.InternalServerError, None, None).responseStatus mustBe Status.InternalServerError
    HttpMetric(Method.GET, uri"/", 1.second, Status.Unauthorized, None, None).responseStatus mustBe Status.Unauthorized
  }

  it should "handle various content types" in {
    val jsonMetric = HttpMetric(Method.GET, uri"/", 1.second, Status.Ok, Some(MediaType.application.json), None)
    jsonMetric.contentType mustBe Some(MediaType.application.json)

    val htmlMetric = HttpMetric(Method.GET, uri"/", 1.second, Status.Ok, Some(MediaType.text.html), None)
    htmlMetric.contentType mustBe Some(MediaType.text.html)

    val videoMetric = HttpMetric(Method.GET, uri"/", 1.second, Status.Ok, Some(MediaType.video.mp4), None)
    videoMetric.contentType mustBe Some(MediaType.video.mp4)
  }
}
