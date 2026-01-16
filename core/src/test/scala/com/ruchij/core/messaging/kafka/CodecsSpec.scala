package com.ruchij.core.messaging.kafka

import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.scheduling.models.ScheduledVideoDownload.ErrorInfo
import com.ruchij.core.daos.scheduling.models.SchedulingStatus
import com.ruchij.core.daos.videometadata.models.{CustomVideoSite, VideoMetadata, VideoSite}
import org.http4s.{MediaType, Method, Status, Uri}
import org.joda.time.DateTime
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import vulcan.{AvroError, Codec}

import scala.concurrent.duration._

class CodecsSpec extends AnyFlatSpec with Matchers {
  import Codecs._

  "dateTimeCodec" should "encode and decode DateTime correctly" in {
    val dateTime = new DateTime(2024, 5, 15, 10, 30, 45, 123)

    roundTrip(dateTime) mustBe Right(dateTime)
  }

  it should "preserve millisecond precision" in {
    val now = DateTime.now()

    roundTrip(now) mustBe Right(now)
  }

  it should "handle epoch time" in {
    val epoch = new DateTime(0L)

    roundTrip(epoch) mustBe Right(epoch)
  }

  "videoSiteCodec" should "encode and decode YTDownloaderSite" in {
    val site: VideoSite = VideoSite.YTDownloaderSite("youtube")

    roundTrip(site) mustBe Right(site)
  }

  it should "encode and decode SpankBang custom site" in {
    val site: VideoSite = CustomVideoSite.SpankBang

    roundTrip(site) mustBe Right(site)
  }

  it should "encode and decode Local video site" in {
    val site: VideoSite = VideoSite.Local

    roundTrip(site) mustBe Right(site)
  }

  "enumCodec" should "encode and decode SchedulingStatus" in {
    val status: SchedulingStatus = SchedulingStatus.Queued

    roundTrip(status) mustBe Right(status)
  }

  it should "encode and decode all SchedulingStatus values" in {
    SchedulingStatus.values.foreach { status =>
      roundTrip(status) mustBe Right(status)
    }
  }

  it should "handle case insensitive decoding" in {
    val codec = enumCodec[SchedulingStatus]
    val stringCodec = Codec[String]

    val encoded = stringCodec.encode("queued")
    val decoded = encoded.flatMap(codec.decode(_, codec.schema.toOption.get))

    decoded mustBe Right(SchedulingStatus.Queued)
  }

  "finiteDurationCodec" should "encode and decode durations" in {
    val duration = 5.minutes

    roundTrip(duration) mustBe Right(duration)
  }

  it should "handle zero duration" in {
    val duration = 0.seconds

    roundTrip(duration) mustBe Right(duration)
  }

  it should "handle large durations" in {
    val duration = 365.days

    roundTrip(duration) mustBe Right(duration)
  }

  it should "preserve millisecond precision" in {
    val duration = 12345.milliseconds

    roundTrip(duration) mustBe Right(duration)
  }

  "uriCodec" should "encode and decode HTTP URIs" in {
    val uri = Uri.unsafeFromString("https://example.com/video?id=123")

    roundTrip(uri) mustBe Right(uri)
  }

  it should "encode and decode file URIs" in {
    val uri = Uri.unsafeFromString("/home/videos/test.mp4")

    roundTrip(uri) mustBe Right(uri)
  }

  it should "handle URIs with special characters" in {
    val uri = Uri.unsafeFromString("https://example.com/video?name=test%20video")

    roundTrip(uri) mustBe Right(uri)
  }

  it should "fail on invalid URI strings" in {
    val codec = uriCodec
    val stringCodec = Codec[String]

    val encoded = stringCodec.encode("://invalid-uri")
    val decoded = encoded.flatMap(codec.decode(_, codec.schema.toOption.get))

    decoded.isLeft mustBe true
  }

  "mediaTypeCodec" should "encode and decode video/mp4" in {
    val mediaType = MediaType.video.mp4

    roundTrip(mediaType) mustBe Right(mediaType)
  }

  it should "encode and decode image/jpeg" in {
    val mediaType = MediaType.image.jpeg

    roundTrip(mediaType) mustBe Right(mediaType)
  }

  it should "encode and decode application/json" in {
    val mediaType = MediaType.application.json

    roundTrip(mediaType) mustBe Right(mediaType)
  }

  it should "fail on invalid media type strings" in {
    val codec = mediaTypeCodec
    val stringCodec = Codec[String]

    val encoded = stringCodec.encode("invalid/media/type/format")
    val decoded = encoded.flatMap(codec.decode(_, codec.schema.toOption.get))

    decoded.isLeft mustBe true
  }

  "statusCodec" should "encode and decode 200 OK" in {
    val status = Status.Ok

    roundTrip(status) mustBe Right(status)
  }

  it should "encode and decode 404 NotFound" in {
    val status = Status.NotFound

    roundTrip(status) mustBe Right(status)
  }

  it should "encode and decode 500 InternalServerError" in {
    val status = Status.InternalServerError

    roundTrip(status) mustBe Right(status)
  }

  it should "fail on invalid status codes" in {
    val codec = statusCodec
    val intCodec = Codec[Int]

    val encoded = intCodec.encode(999)
    val decoded = encoded.flatMap(codec.decode(_, codec.schema.toOption.get))

    decoded.isLeft mustBe true
  }

  "methodCodec" should "encode and decode GET" in {
    val method = Method.GET

    roundTrip(method) mustBe Right(method)
  }

  it should "encode and decode POST" in {
    val method = Method.POST

    roundTrip(method) mustBe Right(method)
  }

  it should "encode and decode DELETE" in {
    val method = Method.DELETE

    roundTrip(method) mustBe Right(method)
  }

  it should "parse non-standard method strings" in {
    val codec = methodCodec
    val stringCodec = Codec[String]

    // HTTP methods can be custom/extended, so this should succeed
    val encoded = stringCodec.encode("CUSTOM")
    val decoded = encoded.flatMap(codec.decode(_, codec.schema.toOption.get))

    decoded.isRight mustBe true
    decoded.toOption.get.name mustBe "CUSTOM"
  }

  "fileResourceCodec" should "encode and decode FileResource" in {
    val fileResource = FileResource(
      "resource-1",
      new DateTime(2024, 5, 15, 10, 30),
      "/videos/test.mp4",
      MediaType.video.mp4,
      1024 * 1024L
    )

    roundTrip(fileResource) mustBe Right(fileResource)
  }

  it should "handle zero-size file resources" in {
    val fileResource = FileResource(
      "empty-resource",
      new DateTime(2024, 5, 15, 10, 30),
      "/videos/empty.mp4",
      MediaType.video.mp4,
      0L
    )

    roundTrip(fileResource) mustBe Right(fileResource)
  }

  "videoMetadataCodec" should "encode and decode VideoMetadata" in {
    val thumbnail = FileResource(
      "thumb-1",
      new DateTime(2024, 5, 15, 10, 30),
      "/images/thumb.jpg",
      MediaType.image.jpeg,
      5000L
    )

    val videoMetadata = VideoMetadata(
      Uri.unsafeFromString("https://youtube.com/watch?v=abc123"),
      "video-123",
      VideoSite.YTDownloaderSite("youtube"),
      "Test Video Title",
      10.minutes,
      100 * 1024 * 1024L,
      thumbnail
    )

    roundTrip(videoMetadata) mustBe Right(videoMetadata)
  }

  "errorInfoCodec" should "encode and decode ErrorInfo" in {
    val errorInfo = ErrorInfo("Something went wrong", "Internal server error occurred")

    roundTrip(errorInfo) mustBe Right(errorInfo)
  }

  it should "handle different error messages" in {
    val errorInfo = ErrorInfo("Not found", "The requested resource was not found")

    roundTrip(errorInfo) mustBe Right(errorInfo)
  }

  it should "handle long error messages" in {
    val longMessage = "A" * 1000
    val errorInfo = ErrorInfo(longMessage, "Details: " + longMessage)

    roundTrip(errorInfo) mustBe Right(errorInfo)
  }

  private def roundTrip[A](value: A)(implicit codec: Codec[A]): Either[AvroError, A] = {
    for {
      schema <- codec.schema
      encoded <- codec.encode(value)
      decoded <- codec.decode(encoded, schema)
    } yield decoded
  }

  "enumCodec error handling" should "return error for invalid enum value" in {
    val codec = enumCodec[SchedulingStatus]
    val stringCodec = Codec[String]

    val encoded = stringCodec.encode("NonExistent")
    val decoded = encoded.flatMap(codec.decode(_, codec.schema.toOption.get))

    decoded.isLeft mustBe true
  }

  "mediaTypeCodec error handling" should "return error for malformed media type" in {
    val codec = mediaTypeCodec
    val stringCodec = Codec[String]

    val encoded = stringCodec.encode("not-a-media-type")
    val decoded = encoded.flatMap(codec.decode(_, codec.schema.toOption.get))

    decoded.isLeft mustBe true
  }

  "methodCodec" should "handle empty string" in {
    val codec = methodCodec
    val stringCodec = Codec[String]

    val encoded = stringCodec.encode("")
    val decoded = encoded.flatMap(codec.decode(_, codec.schema.toOption.get))

    // Empty string might fail or produce a Method
    decoded.isLeft || decoded.toOption.get.name == "" mustBe true
  }
}
