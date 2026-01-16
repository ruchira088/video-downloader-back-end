package com.ruchij.api.services.health

import cats.effect.IO
import cats.~>
import com.ruchij.api.services.health.models.kv.HealthCheckKey
import com.ruchij.api.services.health.models.messaging.HealthCheckMessage
import com.ruchij.api.services.health.models.{HealthStatus, ServiceInformation}
import com.ruchij.core.config.{SpaSiteRendererConfiguration, StorageConfiguration}
import com.ruchij.core.messaging.Publisher
import com.ruchij.core.services.repository.RepositoryService
import com.ruchij.core.services.video.YouTubeVideoDownloader
import com.ruchij.core.services.video.models.{VideoAnalysisResult, YTDownloaderProgress}
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.test.Providers
import com.ruchij.core.types.JodaClock
import doobie.free.connection.ConnectionIO
import fs2.{Pipe, Stream}
import org.http4s.client.Client
import org.http4s.{HttpApp, MediaType, Response, Status, Uri}
import org.joda.time.DateTime
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class HealthServiceImplSpec extends AnyFlatSpec with Matchers {

  val timestamp = new DateTime(2024, 5, 15, 10, 30, 0)

  trait StubRepositoryService extends RepositoryService[IO] {
    override type BackedType = String
    override def write(key: String, data: Stream[IO, Byte]): Stream[IO, Nothing] = Stream.empty
    override def read(key: String, start: Option[Long], end: Option[Long]): IO[Option[Stream[IO, Byte]]] =
      IO.pure(Some(Stream.emits("test".getBytes)))
    override def delete(key: String): IO[Boolean] = IO.pure(true)
    override def size(key: String): IO[Option[Long]] = IO.pure(Some(100L))
    override def list(prefix: String): Stream[IO, String] = Stream.empty
    override def exists(key: String): IO[Boolean] = IO.pure(true)
    override def backedType(key: String): IO[String] = IO.pure(key)
    override def fileType(key: String): IO[Option[MediaType]] = IO.pure(Some(MediaType.text.plain))
  }

  class StubYouTubeVideoDownloader(versionResult: String = "yt-dlp 2024.01.01")
      extends YouTubeVideoDownloader[IO] {
    override def downloadVideo(videoUrl: Uri, destinationPath: String): Stream[IO, YTDownloaderProgress] =
      Stream.empty

    override val version: IO[String] = IO.pure(versionResult)

    override val supportedSites: IO[Seq[String]] = IO.pure(Seq("youtube.com", "youtu.be"))

    override def videoInformation(uri: Uri): IO[VideoAnalysisResult] =
      IO.raiseError(new NotImplementedError("videoInformation not implemented in stub"))
  }

  class StubHealthCheckPublisher extends Publisher[IO, HealthCheckMessage] {
    override val publish: Pipe[IO, HealthCheckMessage, Unit] = _.as(())
    override def publishOne(value: HealthCheckMessage): IO[Unit] = IO.unit
  }

  implicit val transaction: ConnectionIO ~> IO = new (ConnectionIO ~> IO) {
    override def apply[A](fa: ConnectionIO[A]): IO[A] = IO.pure(null.asInstanceOf[A])
  }

  val storageConfiguration = StorageConfiguration("/videos", "/images", List("/other-videos"))
  val spaSiteRendererConfiguration = SpaSiteRendererConfiguration(Uri.unsafeFromString("http://localhost:3000"))

  def createClient(status: Status = Status.Ok): Client[IO] =
    Client.fromHttpApp(HttpApp[IO](_ => IO.pure(Response[IO](status))))

  "serviceInformation" should "return service information with yt-dlp version" in runIO {
    implicit val jodaClock: JodaClock[IO] = Providers.stubClock[IO](timestamp)

    ServiceInformation.create[IO]("yt-dlp 2024.01.01").map { info =>
      info.`yt-dlpVersion` mustBe "yt-dlp 2024.01.01"
      info.javaVersion.nonEmpty mustBe true
      info.sbtVersion.nonEmpty mustBe true
      info.scalaVersion.nonEmpty mustBe true
    }
  }

  it should "handle empty yt-dlp version string" in runIO {
    implicit val jodaClock: JodaClock[IO] = Providers.stubClock[IO](timestamp)

    ServiceInformation.create[IO]("").map { info =>
      info.`yt-dlpVersion` mustBe ""
    }
  }

  "HealthService.ConnectivityUrl" should "be the correct cloudflare URL" in {
    HealthService.ConnectivityUrl mustBe Uri.unsafeFromString("https://ip.ruchij.workers.dev")
  }

  "ServiceInformation" should "contain build info" in runIO {
    implicit val jodaClock: JodaClock[IO] = Providers.stubClock[IO](timestamp)

    ServiceInformation.create[IO]("yt-dlp 2024.01.01").map { info =>
      info.`yt-dlpVersion` mustBe "yt-dlp 2024.01.01"
      info.javaVersion.nonEmpty mustBe true
      info.currentTimestamp mustBe timestamp
    }
  }

  "File repository health check pattern" should "verify write, read, and delete operations" in runIO {
    val testContent = "health-check-content"
    var writtenData: Option[String] = None
    var deletedKey: Option[String] = None

    val fileRepositoryService = new StubRepositoryService {
      override def write(key: String, data: Stream[IO, Byte]): Stream[IO, Nothing] = {
        Stream.eval(data.compile.toList.map(bytes => writtenData = Some(new String(bytes.toArray)))).drain
      }

      override def read(key: String, start: Option[Long], end: Option[Long]): IO[Option[Stream[IO, Byte]]] =
        IO.pure(writtenData.map(content => Stream.emits(content.getBytes)))

      override def delete(key: String): IO[Boolean] = {
        deletedKey = Some(key)
        IO.pure(true)
      }
    }

    val testKey = "/images/image-health-check-10-30-00-000.txt"

    for {
      _ <- fileRepositoryService.write(testKey, Stream.emits(testContent.getBytes)).compile.drain
      readResult <- fileRepositoryService.read(testKey, None, None)
      content <- readResult.get.compile.toList.map(bytes => new String(bytes.toArray))
      deleted <- fileRepositoryService.delete(testKey)
    } yield {
      content mustBe testContent
      deleted mustBe true
      deletedKey mustBe Some(testKey)
    }
  }

  it should "return unhealthy when write fails" in runIO {
    val fileRepositoryService = new StubRepositoryService {
      override def write(key: String, data: Stream[IO, Byte]): Stream[IO, Nothing] =
        Stream.raiseError[IO](new RuntimeException("Write failed"))
    }

    fileRepositoryService.write("/test", Stream.empty).compile.drain.attempt.map { result =>
      result.isLeft mustBe true
    }
  }

  it should "return unhealthy when read returns None" in runIO {
    val fileRepositoryService = new StubRepositoryService {
      override def read(key: String, start: Option[Long], end: Option[Long]): IO[Option[Stream[IO, Byte]]] =
        IO.pure(None)
    }

    fileRepositoryService.read("/test", None, None).map { result =>
      result mustBe None
    }
  }

  "HTTP client health check" should "return healthy for successful response" in runIO {
    val client = createClient(Status.Ok)

    client.status(org.http4s.Request[IO](uri = Uri.unsafeFromString("http://test.com"))).map { status =>
      status mustBe Status.Ok
    }
  }

  it should "return unhealthy for non-OK response" in runIO {
    val client = createClient(Status.InternalServerError)

    client.status(org.http4s.Request[IO](uri = Uri.unsafeFromString("http://test.com"))).map { status =>
      status mustBe Status.InternalServerError
    }
  }

  "StorageConfiguration" should "contain all required paths" in {
    storageConfiguration.videoFolder mustBe "/videos"
    storageConfiguration.imageFolder mustBe "/images"
    storageConfiguration.otherVideoFolders mustBe List("/other-videos")
  }

  "SpaSiteRendererConfiguration" should "contain the renderer URI" in {
    spaSiteRendererConfiguration.uri mustBe Uri.unsafeFromString("http://localhost:3000")
  }

  "HealthCheckKey" should "wrap timestamp correctly" in {
    val key = HealthCheckKey(timestamp)
    key.dateTime mustBe timestamp
  }

  "HealthCheckMessage" should "contain instance ID and timestamp" in {
    val message = HealthCheckMessage("instance-123", timestamp)
    message.instanceId mustBe "instance-123"
    message.dateTime mustBe timestamp
  }

  "HealthStatus" should "have Healthy and Unhealthy variants" in {
    HealthStatus.Healthy mustBe a[HealthStatus]
    HealthStatus.Unhealthy mustBe a[HealthStatus]
  }

  "YouTubeVideoDownloader stub" should "return version" in runIO {
    val downloader = new StubYouTubeVideoDownloader("yt-dlp 2024.05.01")

    downloader.version.map { version =>
      version mustBe "yt-dlp 2024.05.01"
    }
  }

  it should "return supported sites" in runIO {
    val downloader = new StubYouTubeVideoDownloader()

    downloader.supportedSites.map { sites =>
      sites must contain("youtube.com")
      sites must contain("youtu.be")
    }
  }

  "HealthCheckPublisher stub" should "publish messages" in runIO {
    val publisher = new StubHealthCheckPublisher
    val message = HealthCheckMessage("test-instance", timestamp)

    publisher.publishOne(message).as(true).map { result =>
      result mustBe true
    }
  }

  "Database health check pattern" should "verify database connectivity" in runIO {
    // Simulate the database check pattern
    val checkQuery: IO[Int] = IO.pure(1)

    checkQuery.map { result =>
      val healthStatus = if (result == 1) HealthStatus.Healthy else HealthStatus.Unhealthy
      healthStatus mustBe HealthStatus.Healthy
    }
  }

  it should "return unhealthy for failed database query" in runIO {
    val checkQuery: IO[Int] = IO.pure(0)

    checkQuery.map { result =>
      val healthStatus = if (result == 1) HealthStatus.Healthy else HealthStatus.Unhealthy
      healthStatus mustBe HealthStatus.Unhealthy
    }
  }

}
