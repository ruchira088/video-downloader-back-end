package com.ruchij.core.circe

import com.ruchij.core.circe.Decoders._
import com.ruchij.core.circe.Encoders._
import com.ruchij.core.daos.scheduling.models.SchedulingStatus
import com.ruchij.core.daos.videometadata.models.{CustomVideoSite, VideoSite}
import com.ruchij.core.services.models.{Order, SortBy}
import io.circe.parser._
import io.circe.syntax._
import org.http4s.MediaType
import org.joda.time.DateTime
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

class CirceCodecsSpec extends AnyFlatSpec with Matchers with OptionValues {

  "dateTimeEncoder" should "encode DateTime to ISO string" in {
    val dateTime = new DateTime(2023, 5, 15, 10, 30, 0)
    val json = dateTime.asJson
    json.asString.value must include("2023-05-15")
  }

  "dateTimeDecoder" should "decode valid ISO string to DateTime" in {
    val dateTimeStr = "\"2023-05-15T10:30:00.000Z\""
    val result = decode[DateTime](dateTimeStr)
    result.isRight mustBe true
    result.toOption.get.getYear mustBe 2023
    result.toOption.get.getMonthOfYear mustBe 5
    result.toOption.get.getDayOfMonth mustBe 15
  }

  it should "fail to decode invalid date string" in {
    val invalidStr = "\"not-a-date\""
    val result = decode[DateTime](invalidStr)
    result.isLeft mustBe true
  }

  "throwableEncoder" should "encode exception message" in {
    val exception = new RuntimeException("Test error message")
    val json = exception.asJson
    json.asString.value mustBe "Test error message"
  }

  it should "throw when message is null" in {
    val exception = new RuntimeException(null: String)
    assertThrows[NullPointerException] {
      exception.asJson
    }
  }

  "enumEncoder" should "encode SchedulingStatus enum" in {
    val status: SchedulingStatus = SchedulingStatus.Queued
    val json = status.asJson
    json.asString.value mustBe "Queued"
  }

  it should "encode Order enum" in {
    val order: Order = Order.Ascending
    val json = order.asJson
    json.asString.value mustBe "asc"
  }

  it should "encode SortBy enum" in {
    val sortBy: SortBy = SortBy.Date
    val json = sortBy.asJson
    json.asString.value mustBe "date"
  }

  "enumDecoder" should "decode SchedulingStatus enum" in {
    val statusStr = "\"Completed\""
    val result = decode[SchedulingStatus](statusStr)
    result.isRight mustBe true
    result.toOption.get mustBe SchedulingStatus.Completed
  }

  it should "decode case-insensitively" in {
    val statusStr = "\"queued\""
    val result = decode[SchedulingStatus](statusStr)
    result.isRight mustBe true
    result.toOption.get mustBe SchedulingStatus.Queued
  }

  it should "fail to decode invalid enum value" in {
    val invalidStr = "\"InvalidStatus\""
    val result = decode[SchedulingStatus](invalidStr)
    result.isLeft mustBe true
  }

  "finiteDurationEncoder" should "encode duration with length and unit" in {
    val duration = FiniteDuration(5, TimeUnit.MINUTES)
    val json = duration.asJson

    val cursor = json.hcursor
    cursor.downField("length").as[Long].toOption.value mustBe 5L
    cursor.downField("unit").as[String].toOption.value mustBe "MINUTES"
  }

  it should "encode milliseconds duration" in {
    val duration = FiniteDuration(1500, TimeUnit.MILLISECONDS)
    val json = duration.asJson

    val cursor = json.hcursor
    cursor.downField("length").as[Long].toOption.value mustBe 1500L
    cursor.downField("unit").as[String].toOption.value mustBe "MILLISECONDS"
  }

  "finiteDurationDecoder" should "decode ISO 8601 duration" in {
    val durationStr = "\"PT5M\""
    val result = decode[FiniteDuration](durationStr)
    result.isRight mustBe true
    result.toOption.get.toMillis mustBe 300000L
  }

  it should "decode hours duration" in {
    val durationStr = "\"PT2H30M\""
    val result = decode[FiniteDuration](durationStr)
    result.isRight mustBe true
    result.toOption.get.toMinutes mustBe 150L
  }

  "videoSiteEncoder" should "encode video site to lowercase name" in {
    val site: VideoSite = CustomVideoSite.SpankBang
    val json = site.asJson
    json.asString.value mustBe "spankbang"
  }

  it should "encode PornOne video site" in {
    val site: VideoSite = CustomVideoSite.PornOne
    val json = site.asJson
    json.asString.value mustBe "pornone"
  }

  "videoSiteDecoder" should "decode video site name" in {
    val siteStr = "\"spankbang\""
    val result = decode[VideoSite](siteStr)
    result.isRight mustBe true
    result.toOption.get.name.toLowerCase mustBe "spankbang"
  }

  it should "decode PornHub video site" in {
    val siteStr = "\"pornhub\""
    val result = decode[VideoSite](siteStr)
    result.isRight mustBe true
    result.toOption.get.name.toLowerCase mustBe "pornhub"
  }

  "pathEncoder" should "encode path to absolute string" in {
    val path = Paths.get("/opt/videos/sample.mp4")
    val json = path.asJson
    json.asString.value must include("sample.mp4")
  }

  it should "encode relative path to absolute" in {
    val path = Paths.get("relative/path/file.txt")
    val json = path.asJson
    json.asString.value must include("relative/path/file.txt")
  }

  "mediaTypeEncoder" should "encode media type" in {
    val mediaType = MediaType.video.mp4
    val json = mediaType.asJson
    json.asString.value mustBe "video/mp4"
  }

  it should "encode image media type" in {
    val mediaType = MediaType.image.jpeg
    val json = mediaType.asJson
    json.asString.value mustBe "image/jpeg"
  }

  it should "encode application media type" in {
    val mediaType = MediaType.application.json
    val json = mediaType.asJson
    json.asString.value mustBe "application/json"
  }

  "Encoder and Decoder roundtrip" should "preserve DateTime" in {
    val original = DateTime.now()
    val json = original.asJson
    val decoded = json.as[DateTime]
    decoded.isRight mustBe true
    decoded.toOption.get.getMillis mustBe original.getMillis
  }

  it should "preserve SchedulingStatus" in {
    SchedulingStatus.values.foreach { status =>
      val json = status.asJson
      val decoded = decode[SchedulingStatus](json.noSpaces)
      decoded.isRight mustBe true
      decoded.toOption.get mustBe status
    }
  }

  it should "preserve Order" in {
    Order.values.foreach { order =>
      val json = order.asJson
      val decoded = decode[Order](json.noSpaces)
      decoded.isRight mustBe true
      decoded.toOption.get mustBe order
    }
  }

  it should "preserve SortBy" in {
    SortBy.values.foreach { sortBy =>
      val json = sortBy.asJson
      val decoded = decode[SortBy](json.noSpaces)
      decoded.isRight mustBe true
      decoded.toOption.get mustBe sortBy
    }
  }

  "stringWrapperDecoder" should "decode valid strings for value classes" in {
    import Decoders.stringWrapperDecoder

    // TestWrapper is defined in the companion object below
    val testStr = "\"test-value\""
    val result = decode[TestWrapper](testStr)
    result.isRight mustBe true
    result.toOption.get.value mustBe "test-value"
  }

  it should "fail to decode empty string for value classes" in {
    import Decoders.stringWrapperDecoder

    val emptyStr = "\"\""
    val result = decode[TestWrapper](emptyStr)
    result.isLeft mustBe true
    result.left.exists(_.getMessage.contains("Cannot be empty")) mustBe true
  }

  it should "fail to decode whitespace-only string for value classes" in {
    import Decoders.stringWrapperDecoder

    val whitespaceStr = "\"   \""
    val result = decode[TestWrapper](whitespaceStr)
    result.isLeft mustBe true
    result.left.exists(_.getMessage.contains("Cannot be empty")) mustBe true
  }

}

// Test value class for stringWrapperDecoder tests
final case class TestWrapper(value: String) extends AnyVal
