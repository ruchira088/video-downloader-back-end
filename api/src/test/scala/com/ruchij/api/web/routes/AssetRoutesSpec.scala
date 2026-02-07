package com.ruchij.api.web.routes

import cats.effect.IO
import com.ruchij.api.services.asset.AssetService.FileByteRange
import com.ruchij.api.services.asset.models.Asset
import com.ruchij.api.services.asset.models.Asset.FileRange
import com.ruchij.api.services.authentication.AuthenticationService.Secret
import com.ruchij.api.services.authentication.models.AuthenticationToken
import com.ruchij.api.test.data.ApiTestData
import com.ruchij.api.test.matchers._
import com.ruchij.api.test.mixins.io.MockedRoutesIO
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.exceptions.ResourceNotFoundException
import com.ruchij.core.test.IOSupport.runIO
import fs2.Stream
import org.http4s.headers.{Authorization, Range}
import org.http4s.{AuthScheme, Credentials, MediaType, RangeUnit, Request, Status}
import org.http4s.dsl.io._
import org.http4s.implicits.http4sLiteralsSyntax
import com.ruchij.core.types.TimeUtils
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class AssetRoutesSpec extends AnyFlatSpec with Matchers with MockedRoutesIO {

  private val testTimestamp = TimeUtils.instantOf(2022, 8, 1, 10, 10)
  private val expiresAt = testTimestamp.plus(java.time.Duration.ofDays(45))
  private val testSecret = Secret("test-secret-uuid")
  private val normalUserToken = AuthenticationToken(
    ApiTestData.NormalUser.id,
    testSecret,
    expiresAt,
    testTimestamp,
    0
  )

  private val testThumbnail = FileResource(
    "thumbnail-123",
    testTimestamp,
    "/path/to/thumbnail.jpg",
    MediaType.image.jpeg,
    10096
  )

  private val testSnapshot = FileResource(
    "snapshot-123",
    testTimestamp,
    "/path/to/snapshot.png",
    MediaType.image.png,
    50000
  )

  private val testVideoFile = FileResource(
    "video-123",
    testTimestamp,
    "/path/to/video.mp4",
    MediaType.video.mp4,
    5000000
  )

  private def authHeaders = org.http4s.Headers(
    Authorization(Credentials.Token(AuthScheme.Bearer, testSecret.value))
  )

  private def createAsset(fileResource: FileResource): Asset[IO] = {
    Asset(
      fileResource,
      Stream.emits[IO, Byte]("test data".getBytes.toSeq),
      FileRange(0, fileResource.size)
    )
  }

  "GET /asset/thumbnail/id/:id" should "return a thumbnail image" in runIO {
    (authenticationService.authenticate _)
      .expects(testSecret)
      .returns(IO.pure((normalUserToken, ApiTestData.NormalUser)))

    (assetService.thumbnail _)
      .expects("thumbnail-123")
      .returns(IO.pure(createAsset(testThumbnail)))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = GET,
            uri = uri"/assets/thumbnail/id/thumbnail-123",
            headers = authHeaders
          )
        )
        .flatMap { response =>
          IO.delay {
            response must haveStatus(Status.Ok)
            response.contentType.map(_.mediaType) mustBe Some(MediaType.image.jpeg)
          }
        }
  }

  it should "return not found when thumbnail does not exist" in runIO {
    (authenticationService.authenticate _)
      .expects(testSecret)
      .returns(IO.pure((normalUserToken, ApiTestData.NormalUser)))

    (assetService.thumbnail _)
      .expects("nonexistent-thumbnail")
      .returns(IO.raiseError(ResourceNotFoundException("Thumbnail not found")))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = GET,
            uri = uri"/assets/thumbnail/id/nonexistent-thumbnail",
            headers = authHeaders
          )
        )
        .flatMap { response =>
          IO.delay {
            response must haveStatus(Status.NotFound)
          }
        }
  }

  "GET /asset/snapshot/id/:id" should "return a snapshot image for authenticated user" in runIO {
    (authenticationService.authenticate _)
      .expects(testSecret)
      .returns(IO.pure((normalUserToken, ApiTestData.NormalUser)))

    (assetService.snapshot _)
      .expects("snapshot-123", ApiTestData.NormalUser)
      .returns(IO.pure(createAsset(testSnapshot)))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = GET,
            uri = uri"/assets/snapshot/id/snapshot-123",
            headers = authHeaders
          )
        )
        .flatMap { response =>
          IO.delay {
            response must haveStatus(Status.Ok)
            response.contentType.map(_.mediaType) mustBe Some(MediaType.image.png)
          }
        }
  }

  it should "return unauthorized when not authenticated" in runIO {
    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = GET,
            uri = uri"/assets/snapshot/id/snapshot-123"
          )
        )
        .flatMap { response =>
          IO.delay {
            response must haveStatus(Status.Unauthorized)
          }
        }
  }

  "GET /asset/video/id/:id" should "return a video file for authenticated user" in runIO {
    (authenticationService.authenticate _)
      .expects(testSecret)
      .returns(IO.pure((normalUserToken, ApiTestData.NormalUser)))

    (assetService.videoFile _)
      .expects("video-123", ApiTestData.NormalUser, None, Some(5000000L))
      .returns(IO.pure(createAsset(testVideoFile)))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = GET,
            uri = uri"/assets/video/id/video-123",
            headers = authHeaders
          )
        )
        .flatMap { response =>
          IO.delay {
            response must haveStatus(Status.Ok)
            response.contentType.map(_.mediaType) mustBe Some(MediaType.video.mp4)
          }
        }
  }

  it should "support range requests for video streaming" in runIO {
    (authenticationService.authenticate _)
      .expects(testSecret)
      .returns(IO.pure((normalUserToken, ApiTestData.NormalUser)))

    val rangeAsset = Asset[IO](
      testVideoFile,
      Stream.emits[IO, Byte]("test data".getBytes.toSeq),
      FileRange(0, 999999)
    )

    (assetService.videoFile _)
      .expects("video-123", ApiTestData.NormalUser, Some(FileByteRange(0, Some(999999L))), Some(5000000L))
      .returns(IO.pure(rangeAsset))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = GET,
            uri = uri"/assets/video/id/video-123",
            headers = authHeaders.put(Range(RangeUnit.Bytes, Range.SubRange(0L, 999999L)))
          )
        )
        .flatMap { response =>
          IO.delay {
            response must haveStatus(Status.PartialContent)
            response.contentType.map(_.mediaType) mustBe Some(MediaType.video.mp4)
          }
        }
  }

  it should "return unauthorized when not authenticated" in runIO {
    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = GET,
            uri = uri"/assets/video/id/video-123"
          )
        )
        .flatMap { response =>
          IO.delay {
            response must haveStatus(Status.Unauthorized)
          }
        }
  }

  it should "return not found when video does not exist" in runIO {
    (authenticationService.authenticate _)
      .expects(testSecret)
      .returns(IO.pure((normalUserToken, ApiTestData.NormalUser)))

    (assetService.videoFile _)
      .expects("nonexistent-video", ApiTestData.NormalUser, None, Some(5000000L))
      .returns(IO.raiseError(ResourceNotFoundException("Video file not found")))

    ignoreHttpMetrics() *>
      createRoutes()
        .run(
          Request[IO](
            method = GET,
            uri = uri"/assets/video/id/nonexistent-video",
            headers = authHeaders
          )
        )
        .flatMap { response =>
          IO.delay {
            response must haveStatus(Status.NotFound)
          }
        }
  }
}
