package com.ruchij.api.web.requests

import cats.effect.{IO, Ref}
import com.ruchij.core.exceptions.ValidationException
import com.ruchij.core.test.IOSupport.runIO
import fs2.Stream
import org.http4s.headers.{`Content-Length`, `Content-Type`}
import org.http4s.multipart.{Multiparts, Part}
import org.http4s.{InvalidMessageBodyFailure, MediaType, Method, Request}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class FileAssetSpec extends AnyFlatSpec with Matchers {

  private def decode(fileName: String, mediaType: MediaType) =
    Multiparts.forSync[IO].flatMap { multiparts =>
      multiparts
        .multipart(
          Vector(
            Part.fileData[IO](
              "file",
              fileName,
              Stream.emits[IO, Byte]("data".getBytes.toList),
              `Content-Type`(mediaType)
            )
          )
        )
        .flatMap { multipart =>
          val request = Request[IO](Method.PUT).withEntity(multipart).withHeaders(multipart.headers)

          FileAsset.fileAssetDecoder[IO].decode(request, strict = false).value
        }
    }

  "FileAsset decoder" should "accept raster images" in runIO {
    List(MediaType.image.jpeg, MediaType.image.png, MediaType.image.gif, MediaType.image.webp)
      .map { mediaType =>
        decode("image", mediaType).map { result =>
          result.map(_.mediaType) mustBe Right(mediaType)
        }
      }
      .reduce(_ *> _)
  }

  it should "reject SVG images" in runIO {
    decode("image.svg", MediaType.image.`svg+xml`).map { result =>
      result.left.map(_.getClass) mustBe Left(classOf[InvalidMessageBodyFailure])
    }
  }

  it should "reject other image types" in runIO {
    decode("image.bmp", MediaType.image.bmp).map { result =>
      result.isLeft mustBe true
    }
  }

  private def multipartRequest(fileSize: Int): IO[Request[IO]] =
    Multiparts.forSync[IO].flatMap { multiparts =>
      multiparts
        .multipart(
          Vector(
            Part.fileData[IO](
              "file",
              "art.png",
              Stream.emits[IO, Byte](Array.fill[Byte](fileSize)(1)).chunkLimit(64 * 1024).unchunks,
              `Content-Type`(MediaType.image.png)
            )
          )
        )
        .map { multipart =>
          Request[IO](Method.PUT).withEntity(multipart).withHeaders(multipart.headers)
        }
    }

  it should "reject a body whose Content-Length is over the limit without reading it" in runIO {
    val request =
      Request[IO](Method.PUT)
        .withBodyStream(Stream.raiseError[IO](new IllegalStateException("The body must not be read")))
        .withHeaders(
          `Content-Type`(MediaType.multipart.`form-data`.withExtensions(Map("boundary" -> "boundary"))),
          `Content-Length`.unsafeFromLong(FileAsset.MaxRequestBodySize + 1)
        )

    FileAsset.fileAssetDecoder[IO].decode(request, strict = false).value.attempt.map { result =>
      result.left.map(_.getClass) mustBe Left(classOf[ValidationException])
    }
  }

  it should "stop reading a body without a Content-Length once it is over the limit" in runIO {
    val maxBodySize = 1024 * 1024

    for {
      request <- multipartRequest(fileSize = 4 * maxBodySize)
      bytesRead <- Ref.of[IO, Long](0)
      chunkedRequest =
        request
          .removeHeader[`Content-Length`]
          .withBodyStream(request.body.chunks.evalTap(chunk => bytesRead.update(_ + chunk.size)).unchunks)
      result <- FileAsset.decoder[IO](maxBodySize).decode(chunkedRequest, strict = false).value.attempt
      totalBytesRead <- bytesRead.get
    } yield {
      result.left.map(_.getClass) mustBe Left(classOf[ValidationException])
      totalBytesRead must be <= (maxBodySize + 128 * 1024).toLong
    }
  }

  it should "accept a body within the limit" in runIO {
    multipartRequest(fileSize = 512 * 1024).flatMap { request =>
      FileAsset.decoder[IO](1024 * 1024).decode(request, strict = false).value.map { result =>
        result.map(_.fileName) mustBe Right("art.png")
      }
    }
  }
}
