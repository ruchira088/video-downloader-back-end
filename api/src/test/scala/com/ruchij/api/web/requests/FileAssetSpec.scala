package com.ruchij.api.web.requests

import cats.effect.IO
import com.ruchij.core.test.IOSupport.runIO
import fs2.Stream
import org.http4s.headers.`Content-Type`
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
}
