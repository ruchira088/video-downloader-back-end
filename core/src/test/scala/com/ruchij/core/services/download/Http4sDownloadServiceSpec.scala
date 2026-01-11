package com.ruchij.core.services.download

import cats.effect.IO
import com.ruchij.core.services.repository.RepositoryService
import com.ruchij.core.test.IOSupport.runIO
import fs2.Stream
import org.http4s._
import org.http4s.client.Client
import org.http4s.headers.{`Content-Length`, `Content-Type`}
import org.http4s.implicits.http4sLiteralsSyntax
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.io.File

class Http4sDownloadServiceSpec extends AnyFlatSpec with Matchers {

  class StubRepositoryService(initialSize: Option[Long] = None) extends RepositoryService[IO] {
    override type BackedType = File

    private var writtenData: Array[Byte] = Array.empty

    override def write(key: String, data: Stream[IO, Byte]): Stream[IO, Nothing] =
      data.evalMap(b => IO.delay { writtenData = writtenData :+ b }).drain

    override def read(key: String, start: Option[Long], end: Option[Long]): IO[Option[Stream[IO, Byte]]] =
      IO.pure(Some(Stream.emits(writtenData)))

    override def size(key: String): IO[Option[Long]] = IO.pure(initialSize)

    override def list(key: String): Stream[IO, String] = Stream.empty

    override def exists(key: String): IO[Boolean] = IO.pure(initialSize.isDefined)

    override def backedType(key: String): IO[File] = IO.pure(new File(key))

    override def delete(key: String): IO[Boolean] = IO.pure(true)

    override def fileType(key: String): IO[Option[MediaType]] = IO.pure(Some(MediaType.video.mp4))
  }

  "Http4sDownloadService.download" should "download content and return DownloadResult" in runIO {
    val repositoryService = new StubRepositoryService()
    val fileKey = "test-file"
    val testContent = "Hello World!"
    val contentBytes = testContent.getBytes

    val response = Response[IO](
      status = Status.Ok,
      headers = Headers(
        `Content-Length`.unsafeFromLong(contentBytes.length.toLong),
        `Content-Type`(MediaType.video.mp4)
      ),
      body = Stream.emits(contentBytes)
    )

    val client = Client.fromHttpApp[IO](HttpApp.liftF(IO.pure(response)))
    val downloadService = new Http4sDownloadService[IO](client, repositoryService)

    downloadService
      .download(uri"https://example.com/video.mp4", fileKey)
      .use { downloadResult =>
        IO.delay {
          downloadResult.uri mustBe uri"https://example.com/video.mp4"
          downloadResult.downloadedFileKey mustBe fileKey
          downloadResult.size mustBe contentBytes.length.toLong
          downloadResult.mediaType mustBe MediaType.video.mp4
        }
      }
  }

  it should "resume download from existing file size" in runIO {
    val existingSize = 100L
    val repositoryService = new StubRepositoryService(Some(existingSize))
    val fileKey = "resume-file"
    val newContent = "More content"
    val newContentBytes = newContent.getBytes

    val response = Response[IO](
      status = Status.PartialContent,
      headers = Headers(
        `Content-Length`.unsafeFromLong(newContentBytes.length.toLong),
        `Content-Type`(MediaType.video.mp4)
      ),
      body = Stream.emits(newContentBytes)
    )

    val client = Client.fromHttpApp[IO](HttpApp.liftF(IO.pure(response)))
    val downloadService = new Http4sDownloadService[IO](client, repositoryService)

    downloadService
      .download(uri"https://example.com/video.mp4", fileKey)
      .use { downloadResult =>
        IO.delay {
          downloadResult.size mustBe (existingSize + newContentBytes.length.toLong)
        }
      }
  }

  it should "handle different media types" in runIO {
    val repositoryService = new StubRepositoryService()
    val fileKey = "webm-file"
    val content = "video content"
    val contentBytes = content.getBytes

    val response = Response[IO](
      status = Status.Ok,
      headers = Headers(
        `Content-Length`.unsafeFromLong(contentBytes.length.toLong),
        `Content-Type`(MediaType.video.webm)
      ),
      body = Stream.emits(contentBytes)
    )

    val client = Client.fromHttpApp[IO](HttpApp.liftF(IO.pure(response)))
    val downloadService = new Http4sDownloadService[IO](client, repositoryService)

    downloadService
      .download(uri"https://example.com/video.webm", fileKey)
      .use { downloadResult =>
        IO.delay {
          downloadResult.mediaType mustBe MediaType.video.webm
        }
      }
  }

  it should "track download progress through the data stream" in runIO {
    val repositoryService = new StubRepositoryService()
    val fileKey = "progress-file"
    val content = Array.fill(1000)(0.toByte)

    val response = Response[IO](
      status = Status.Ok,
      headers = Headers(
        `Content-Length`.unsafeFromLong(content.length.toLong),
        `Content-Type`(MediaType.video.mp4)
      ),
      body = Stream.emits(content)
    )

    val client = Client.fromHttpApp[IO](HttpApp.liftF(IO.pure(response)))
    val downloadService = new Http4sDownloadService[IO](client, repositoryService)

    downloadService
      .download(uri"https://example.com/video.mp4", fileKey)
      .use { downloadResult =>
        downloadResult.data.compile.toVector.map { progressValues =>
          progressValues.head mustBe 0L
          progressValues.last mustBe content.length.toLong
        }
      }
  }
}
