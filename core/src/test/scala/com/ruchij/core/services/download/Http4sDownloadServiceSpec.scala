package com.ruchij.core.services.download

import cats.effect.{IO, Ref, Resource}
import cats.implicits._
import com.ruchij.core.exceptions.ExternalServiceException
import com.ruchij.core.services.repository.{FileRepositoryService, FileTypeDetector}
import com.ruchij.core.test.IOSupport.runIO
import fs2.{Stream, text}
import fs2.io.file.{Files, Path}
import org.http4s._
import org.http4s.client.Client
import org.http4s.headers.{ETag, Range, `Content-Length`, `Content-Type`, `If-Range`}
import org.http4s.implicits.http4sLiteralsSyntax
import org.scalatest.Assertion
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import org.typelevel.ci.CIString

import scala.concurrent.duration.DurationInt

class Http4sDownloadServiceSpec extends AnyFlatSpec with Matchers {
  private val uri = uri"https://example.com/video.mp4"

  private class Fixture(val repository: FileRepositoryService[IO], val key: String) {
    val metadataKey: String = s"$key.http-download.json"

    def write(path: String, content: String): IO[Unit] =
      repository.write(path, Stream.emit(content).through(text.utf8.encode)).compile.drain

    def read(path: String): IO[String] =
      repository.read(path, None, None).flatMap(_.fold(IO.pure(""))(_.through(text.utf8.decode).compile.string))

    def seedPartial: IO[Unit] =
      write(key, "abc") *> write(metadataKey,
        """{"uri":"https://example.com/video.mp4","etag":"\"v1\"","size":6}""")

    def service(response: Response[IO]): Http4sDownloadService[IO] =
      // fromHttpApp drains response bodies on release, which cannot terminate for the cancellation fixture.
      new Http4sDownloadService[IO](Client[IO](_ => Resource.pure[IO, Response[IO]](response)), repository)

    def consume(response: Response[IO]): IO[Vector[Long]] =
      service(response).download(uri, key).use(_.data.compile.toVector)
  }

  private def withFile(test: Fixture => IO[Assertion]): IO[Assertion] =
    Resource.make(Files[IO].createTempDirectory)(Files[IO].deleteRecursively).use { directory =>
      val detector = new FileTypeDetector[IO, Path] {
        override def detect(path: Path): IO[MediaType] = IO.pure(MediaType.video.mp4)
      }
      test(new Fixture(new FileRepositoryService[IO](detector), directory.resolve("video.mp4").toString))
    }

  private def response(
    body: String = "abcdef",
    status: Status = Status.Ok,
    length: Long = 6L,
    extraHeaders: List[Header.Raw] = Nil
  ): Response[IO] =
    Response[IO](status = status,
      headers = Headers(`Content-Length`.unsafeFromLong(length), `Content-Type`(MediaType.video.mp4)) ++
        Headers(extraHeaders),
      body = Stream.emit(body).through(text.utf8.encode))

  private def partialResponse: Response[IO] =
    response("def", Status.PartialContent, 3,
      List(Header.Raw(CIString("Content-Range"), "bytes 3-5/6"), Header.Raw(CIString("ETag"), "\"v1\"")))

  private def assertDownloadError(result: Either[Throwable, Any]): Assertion = {
    result.left.toOption.get mustBe a[ExternalServiceException]
    result.left.toOption.get.getMessage must include(uri.renderString)
  }

  "Http4sDownloadService.download" should "write the body, report metadata and count bytes" in runIO {
    withFile { fixture =>
      fixture.service(response()).download(uri, fixture.key).use { result =>
        for {
          progress <- result.data.compile.toVector
          content <- fixture.read(fixture.key)
          metadataExists <- fixture.repository.exists(fixture.metadataKey)
        } yield {
          result.uri mustBe uri
          result.downloadedFileKey mustBe fixture.key
          result.size mustBe 6L
          result.mediaType mustBe MediaType.video.mp4
          progress.head mustBe 0L
          progress.last mustBe 6L
          content mustBe "abcdef"
          metadataExists mustBe false
        }
      }
    }
  }

  it should "resume a validated partial file with Range and If-Range" in runIO {
    withFile { fixture =>
      val client = Client.fromHttpApp[IO](HttpApp[IO] { request =>
        IO {
          request.headers.get[Range] mustBe Some(Range(3L))
          request.headers.get[`If-Range`] mustBe Some(`If-Range`.ETag(EntityTag("v1")))
          request.headers.headers must contain(Header.Raw(CIString("Accept-Encoding"), "identity"))
          partialResponse
        }
      })
      for {
        _ <- fixture.seedPartial
        progress <- new Http4sDownloadService[IO](client, fixture.repository).download(uri, fixture.key)
          .use(_.data.compile.toVector)
        content <- fixture.read(fixture.key)
        metadataExists <- fixture.repository.exists(fixture.metadataKey)
      } yield {
        progress.head mustBe 3L
        progress.last mustBe 6L
        content mustBe "abcdef"
        metadataExists mustBe false
      }
    }
  }

  it should "restart when a server ignores Range or the representation has changed" in runIO {
    withFile { fixture =>
      for {
        _ <- fixture.seedPartial
        progress <- fixture.consume(response("uvwxyz"))
        content <- fixture.read(fixture.key)
      } yield {
        progress.head mustBe 0L
        progress.last mustBe 6L
        content mustBe "uvwxyz"
      }
    }
  }

  for ((description, metadata) <- List(
    "missing" -> None,
    "malformed" -> Some("not JSON"),
    "oversized" -> Some("x" * 16385),
    "weak" -> Some("""{"uri":"https://example.com/video.mp4","etag":"W/\"v1\"","size":6}"""),
    "different URL" -> Some("""{"uri":"https://example.com/other","etag":"\"v1\"","size":6}"""),
    "invalid ETag" -> Some("""{"uri":"https://example.com/video.mp4","etag":"invalid","size":6}"""),
    "complete file" -> Some("""{"uri":"https://example.com/video.mp4","etag":"\"v1\"","size":3}""")
  )) {
    it should s"restart without a range when resume metadata is $description" in runIO {
      withFile { fixture =>
        val client = Client.fromHttpApp[IO](HttpApp[IO] { request =>
          IO {
            request.headers.get[Range] mustBe None
            request.headers.get[`If-Range`] mustBe None
            response()
          }
        })
        for {
          _ <- fixture.write(fixture.key, "old")
          _ <- metadata.traverse_(fixture.write(fixture.metadataKey, _))
          _ <- new Http4sDownloadService[IO](client, fixture.repository).download(uri, fixture.key)
            .use(_.data.compile.drain)
          content <- fixture.read(fixture.key)
        } yield content mustBe "abcdef"
      }
    }
  }

  for (status <- List(Status.NotFound, Status.InternalServerError, Status.Found, Status.NoContent,
    Status.RangeNotSatisfiable)) {
    it should s"reject $status without changing an existing partial file" in runIO {
      withFile { fixture =>
        for {
          _ <- fixture.seedPartial
          result <- fixture.consume(response(status = status)).attempt
          content <- fixture.read(fixture.key)
          metadataExists <- fixture.repository.exists(fixture.metadataKey)
        } yield {
          assertDownloadError(result)
          content mustBe "abc"
          metadataExists mustBe true
        }
      }
    }
  }

  for (range <- List(None, Some("invalid"), Some("bytes 0-2/6"), Some("bytes 3-4/6"),
    Some("bytes 3-5/7"), Some("bytes 3-5/*"), Some("items 3-5/6"))) {
    it should s"reject a partial response with Content-Range $range" in runIO {
      withFile { fixture =>
        for {
          _ <- fixture.seedPartial
          result <- fixture.consume(response("def", Status.PartialContent, 3,
            range.toList.map(value => Header.Raw(CIString("Content-Range"), value)))).attempt
          content <- fixture.read(fixture.key)
        } yield {
          assertDownloadError(result)
          content mustBe "abc"
        }
      }
    }
  }

  it should "reject a partial response with an inconsistent Content-Length" in runIO {
    withFile { fixture =>
      for {
        _ <- fixture.seedPartial
        result <- fixture.consume(partialResponse.putHeaders(`Content-Length`.unsafeFromLong(2L))).attempt
        content <- fixture.read(fixture.key)
      } yield {
        assertDownloadError(result)
        content mustBe "abc"
      }
    }
  }

  it should "reject a changed ETag in a partial response" in runIO {
    withFile { fixture =>
      for {
        _ <- fixture.seedPartial
        result <- fixture.consume(partialResponse.putHeaders(ETag("v2"))).attempt
        content <- fixture.read(fixture.key)
      } yield {
        assertDownloadError(result)
        content mustBe "abc"
      }
    }
  }

  it should "reject a malformed ETag in a partial response" in runIO {
    withFile { fixture =>
      for {
        _ <- fixture.seedPartial
        result <- fixture.consume(partialResponse.putHeaders(Header.Raw(CIString("ETag"), "invalid"))).attempt
        content <- fixture.read(fixture.key)
      } yield {
        assertDownloadError(result)
        content mustBe "abc"
      }
    }
  }

  it should "accept a validated range when the response omits the ETag" in runIO {
    withFile { fixture =>
      for {
        _ <- fixture.seedPartial
        _ <- fixture.consume(partialResponse.transformHeaders(headers =>
          Headers(headers.headers.filterNot(_.name == CIString("ETag")))))
        content <- fixture.read(fixture.key)
      } yield content mustBe "abcdef"
    }
  }

  it should "reject an unsolicited partial response" in runIO {
    withFile { fixture => fixture.consume(partialResponse).attempt.map(assertDownloadError) }
  }

  for (header <- List(CIString("Content-Length"), CIString("Content-Type"))) {
    it should s"reject a missing $header header before replacing an existing file" in runIO {
      withFile { fixture =>
        for {
          _ <- fixture.seedPartial
          result <- fixture.consume(response().transformHeaders(value =>
            Headers(value.headers.filterNot(_.name == header)))).attempt
          content <- fixture.read(fixture.key)
        } yield {
          assertDownloadError(result)
          content mustBe "abc"
        }
      }
    }
  }

  it should "reject encoded bodies before replacing an existing file" in runIO {
    withFile { fixture =>
      for {
        _ <- fixture.seedPartial
        result <- fixture.consume(response(extraHeaders = List(Header.Raw(CIString("Content-Encoding"), "gzip"))))
          .attempt
        content <- fixture.read(fixture.key)
      } yield {
        assertDownloadError(result)
        content mustBe "abc"
      }
    }
  }

  it should "preserve a short download and resume it using its saved validator" in runIO {
    withFile { fixture =>
      for {
        first <- fixture.consume(response("abc").putHeaders(ETag("v1"))).attempt
        contentAfterFailure <- fixture.read(fixture.key)
        metadataExists <- fixture.repository.exists(fixture.metadataKey)
        _ <- fixture.consume(partialResponse)
        content <- fixture.read(fixture.key)
      } yield {
        assertDownloadError(first)
        contentAfterFailure mustBe "abc"
        metadataExists mustBe true
        content mustBe "abcdef"
      }
    }
  }

  for (etag <- List(None, Some(ETag("v1", EntityTag.Weak)))) {
    it should s"restart interrupted downloads when the response has no strong validator: $etag" in runIO {
      withFile { fixture =>
        val firstResponse = etag.fold(response("abc"))(value => response("abc").putHeaders(value))
        val client = Client.fromHttpApp[IO](HttpApp[IO] { request =>
          IO {
            request.headers.get[Range] mustBe None
            response("uvwxyz")
          }
        })
        for {
          first <- fixture.consume(firstResponse).attempt
          metadataExists <- fixture.repository.exists(fixture.metadataKey)
          _ <- new Http4sDownloadService[IO](client, fixture.repository).download(uri, fixture.key)
            .use(_.data.compile.drain)
          content <- fixture.read(fixture.key)
        } yield {
          assertDownloadError(first)
          metadataExists mustBe false
          content mustBe "uvwxyz"
        }
      }
    }
  }

  it should "keep resume metadata when a consumer cancels a download" in runIO {
    withFile { fixture =>
      for {
        ready <- cats.effect.Deferred[IO, Unit]
        firstResponse = response().putHeaders(ETag("v1")).copy(body =
          Stream.emit("abc").through(text.utf8.encode) ++ Stream.exec(ready.complete(()).void) ++ Stream.never[IO])
        fiber <- fixture.consume(firstResponse).start
        _ <- ready.get.timeoutAndForget(5.seconds)
        _ <- fiber.cancel.timeoutAndForget(5.seconds)
        contentAfterCancellation <- fixture.read(fixture.key)
        _ <- fixture.consume(partialResponse)
        content <- fixture.read(fixture.key)
      } yield {
        contentAfterCancellation mustBe "abc"
        content mustBe "abcdef"
      }
    }
  }

  it should "reject oversized bodies before appending an oversized chunk" in runIO {
    withFile { fixture =>
      for {
        _ <- fixture.seedPartial
        result <- fixture.consume(partialResponse.copy(body = Stream.emit("defg").through(text.utf8.encode))).attempt
        content <- fixture.read(fixture.key)
      } yield {
        assertDownloadError(result)
        content mustBe "abc"
      }
    }
  }

  it should "propagate a transport failure and release the response" in runIO {
    withFile { fixture =>
      val error = new java.io.IOException("connection lost")
      for {
        released <- Ref.of[IO, Boolean](false)
        client = Client[IO] { _ =>
          Resource.make(IO.pure(response().copy(body = Stream.raiseError[IO](error))))(_ => released.set(true))
        }
        result <- new Http4sDownloadService[IO](client, fixture.repository).download(uri, fixture.key)
          .use(_.data.compile.drain).attempt
        wasReleased <- released.get
      } yield {
        result mustBe Left(error)
        wasReleased mustBe true
      }
    }
  }

  it should "leave existing files untouched when the result body is not consumed" in runIO {
    withFile { fixture =>
      for {
        _ <- fixture.seedPartial
        _ <- fixture.service(response()).download(uri, fixture.key).use(_ => IO.unit)
        content <- fixture.read(fixture.key)
      } yield content mustBe "abc"
    }
  }

  it should "write an empty file for an empty successful response" in runIO {
    withFile { fixture =>
      for {
        _ <- fixture.consume(response("", length = 0L))
        size <- fixture.repository.size(fixture.key)
      } yield size mustBe Some(0L)
    }
  }
}
