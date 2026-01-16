package com.ruchij.core.services.video

import cats.effect.{IO, Resource}
import cats.~>
import com.ruchij.core.config.StorageConfiguration
import com.ruchij.core.daos.resource.FileResourceDao
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.videometadata.VideoMetadataDao
import com.ruchij.core.daos.videometadata.models.{VideoMetadata, VideoSite}
import com.ruchij.core.exceptions.ValidationException
import com.ruchij.core.services.cli.CliCommandRunner
import com.ruchij.core.services.download.DownloadService
import com.ruchij.core.services.download.models.DownloadResult
import com.ruchij.core.services.hashing.HashingService
import com.ruchij.core.services.renderer.SpaSiteRenderer
import com.ruchij.core.services.video.VideoAnalysisService.{Existing, NewlyCreated}
import com.ruchij.core.services.video.models.VideoAnalysisResult
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.types.FunctionKTypes.identityFunctionK
import com.ruchij.core.types.JodaClock
import fs2.Stream
import org.http4s._
import org.http4s.client.Client
import org.http4s.implicits.http4sLiteralsSyntax
import org.joda.time.DateTime
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.duration._

class VideoAnalysisServiceImplUnitSpec extends AnyFlatSpec with Matchers {

  implicit val jodaClock: JodaClock[IO] = new JodaClock[IO] {
    override def timestamp: IO[DateTime] = IO.pure(new DateTime(2024, 1, 15, 10, 30, 0, 0))
  }

  implicit val transaction: IO ~> IO = identityFunctionK[IO]

  private val storageConfiguration = StorageConfiguration("/videos", "/images", List.empty)

  class TestHashingService extends HashingService[IO] {
    override def hash(value: String): IO[String] = IO.pure(s"hash-${value.hashCode.abs}")
  }

  class TestCliCommandRunner(output: String) extends CliCommandRunner[IO] {
    override def run(command: String): Stream[IO, String] = Stream.emit(output)
  }

  class TestYouTubeVideoDownloader(result: => IO[VideoAnalysisResult]) extends YouTubeVideoDownloader[IO] {
    override def videoInformation(uri: Uri): IO[VideoAnalysisResult] = result
    override def downloadVideo(uri: Uri, pathWithoutExtension: String): Stream[IO, models.YTDownloaderProgress] = Stream.empty
    override val supportedSites: IO[Seq[String]] = IO.pure(Seq("youtube"))
    override val version: IO[String] = IO.pure("test-version")
  }

  class TestDownloadService(
    downloadResult: DownloadResult[IO]
  ) extends DownloadService[IO] {
    override def download(uri: Uri, fileKey: String): Resource[IO, DownloadResult[IO]] =
      Resource.pure(downloadResult)
  }

  class TestSpaSiteRenderer extends SpaSiteRenderer[IO] {
    override def render(uri: Uri, readyCssSelectors: Seq[String]): IO[String] =
      IO.pure("<html><body>Rendered</body></html>")

    override def executeJavaScript(uri: Uri, readyCssSelectors: Seq[String], script: String): IO[String] =
      IO.pure("js-result")
  }

  class TestVideoMetadataDao(existingMetadata: Option[VideoMetadata] = None) extends VideoMetadataDao[IO] {
    var insertedMetadata: Option[VideoMetadata] = None

    override def insert(videoMetadata: VideoMetadata): IO[Int] = IO {
      insertedMetadata = Some(videoMetadata)
      1
    }

    override def findByUrl(uri: Uri): IO[Option[VideoMetadata]] = IO.pure(existingMetadata)

    override def findById(videoMetadataId: String): IO[Option[VideoMetadata]] = IO.pure(None)

    override def update(videoMetadataId: String, title: Option[String], size: Option[Long], maybeDuration: Option[FiniteDuration]): IO[Int] =
      IO.pure(1)

    override def deleteById(videoMetadataId: String): IO[Int] = IO.pure(1)
  }

  class TestFileResourceDao extends FileResourceDao[IO] {
    var insertedResource: Option[FileResource] = None

    override def insert(fileResource: FileResource): IO[Int] = IO {
      insertedResource = Some(fileResource)
      1
    }

    override def getById(id: String): IO[Option[FileResource]] = IO.pure(None)

    override def findByPath(path: String): IO[Option[FileResource]] = IO.pure(None)

    override def deleteById(id: String): IO[Int] = IO.pure(1)

    override def update(id: String, size: Long): IO[Int] = IO.pure(1)
  }

  "VideoAnalysisServiceImpl.metadata" should "return existing metadata when found" in runIO {
    val existingThumbnail = FileResource(
      "thumb-id",
      new DateTime(2024, 1, 15, 10, 0, 0, 0),
      "/images/thumb.jpg",
      MediaType.image.jpeg,
      5000L
    )
    val existingMetadata = VideoMetadata(
      uri"https://www.youtube.com/watch?v=test123",
      "video-id",
      VideoSite.YTDownloaderSite("youtube"),
      "Existing Video",
      5.minutes,
      100000L,
      existingThumbnail
    )

    val httpClient = Client.fromHttpApp[IO](HttpApp.liftF(IO.pure(Response[IO](Status.Ok))))
    val videoMetadataDao = new TestVideoMetadataDao(Some(existingMetadata))

    val service = new VideoAnalysisServiceImpl[IO, IO](
      new TestHashingService,
      new TestDownloadService(null),
      new TestYouTubeVideoDownloader(IO.raiseError(new Exception("Should not be called"))),
      httpClient,
      new TestSpaSiteRenderer,
      new TestCliCommandRunner(""),
      videoMetadataDao,
      new TestFileResourceDao,
      storageConfiguration
    )

    service.metadata(uri"https://www.youtube.com/watch?v=test123").map { result =>
      result mustBe Existing(existingMetadata)
    }
  }

  it should "create new metadata when not found" in runIO {
    val analysisResult = VideoAnalysisResult(
      uri"https://www.youtube.com/watch?v=newvideo",
      VideoSite.YTDownloaderSite("youtube"),
      "New Video Title",
      10.minutes,
      200000L,
      uri"https://i.ytimg.com/vi/newvideo/hqdefault.jpg"
    )

    val downloadResult = DownloadResult[IO](
      uri"https://i.ytimg.com/vi/newvideo/hqdefault.jpg",
      "/images/thumbnail.jpg",
      5000L,
      MediaType.image.jpeg,
      Stream.emit(5000L)
    )

    val httpClient = Client.fromHttpApp[IO](HttpApp.liftF(IO.pure(Response[IO](Status.Ok))))
    val videoMetadataDao = new TestVideoMetadataDao(None)
    val fileResourceDao = new TestFileResourceDao

    val service = new VideoAnalysisServiceImpl[IO, IO](
      new TestHashingService,
      new TestDownloadService(downloadResult),
      new TestYouTubeVideoDownloader(IO.pure(analysisResult)),
      httpClient,
      new TestSpaSiteRenderer,
      new TestCliCommandRunner(""),
      videoMetadataDao,
      fileResourceDao,
      storageConfiguration
    )

    service.metadata(uri"https://www.youtube.com/watch?v=newvideo").map { result =>
      result mustBe a[NewlyCreated]
      val newMetadata = result.asInstanceOf[NewlyCreated].value
      newMetadata.title mustBe "New Video Title"
      newMetadata.duration mustBe 10.minutes
      newMetadata.size mustBe 200000L
    }
  }

  "VideoAnalysisServiceImpl.analyze" should "return error for invalid URI without host" in runIO {
    val httpClient = Client.fromHttpApp[IO](HttpApp.liftF(IO.pure(Response[IO](Status.Ok))))

    val service = new VideoAnalysisServiceImpl[IO, IO](
      new TestHashingService,
      new TestDownloadService(null),
      new TestYouTubeVideoDownloader(IO.raiseError(new Exception("Should not be called"))),
      httpClient,
      new TestSpaSiteRenderer,
      new TestCliCommandRunner(""),
      new TestVideoMetadataDao(None),
      new TestFileResourceDao,
      storageConfiguration
    )

    // URI without a valid host will fail at VideoSite.fromUri
    service.analyze(Uri.unsafeFromString("/local/video.mp4")).attempt.map { result =>
      result.isLeft mustBe true
      result.left.toOption.get mustBe a[ValidationException]
      result.left.toOption.get.getMessage must include("Unable infer video site")
    }
  }

  it should "use YouTubeVideoDownloader for non-custom video sites" in runIO {
    val analysisResult = VideoAnalysisResult(
      uri"https://www.youtube.com/watch?v=test",
      VideoSite.YTDownloaderSite("youtube"),
      "Test Video",
      5.minutes,
      100000L,
      uri"https://i.ytimg.com/vi/test/hqdefault.jpg"
    )

    val httpClient = Client.fromHttpApp[IO](HttpApp.liftF(IO.pure(Response[IO](Status.Ok))))

    val service = new VideoAnalysisServiceImpl[IO, IO](
      new TestHashingService,
      new TestDownloadService(null),
      new TestYouTubeVideoDownloader(IO.pure(analysisResult)),
      httpClient,
      new TestSpaSiteRenderer,
      new TestCliCommandRunner(""),
      new TestVideoMetadataDao(None),
      new TestFileResourceDao,
      storageConfiguration
    )

    service.analyze(uri"https://www.youtube.com/watch?v=test").map { result =>
      result.title mustBe "Test Video"
      result.duration mustBe 5.minutes
      result.videoSite mustBe VideoSite.YTDownloaderSite("youtube")
    }
  }

  "VideoAnalysisServiceImpl.downloadUri" should "return error for non-custom video sites" in runIO {
    val httpClient = Client.fromHttpApp[IO](HttpApp.liftF(IO.pure(Response[IO](Status.Ok))))

    val service = new VideoAnalysisServiceImpl[IO, IO](
      new TestHashingService,
      new TestDownloadService(null),
      new TestYouTubeVideoDownloader(IO.raiseError(new Exception("Should not be called"))),
      httpClient,
      new TestSpaSiteRenderer,
      new TestCliCommandRunner(""),
      new TestVideoMetadataDao(None),
      new TestFileResourceDao,
      storageConfiguration
    )

    service.downloadUri(uri"https://www.youtube.com/watch?v=test").attempt.map { result =>
      result.isLeft mustBe true
      result.left.toOption.get mustBe a[ValidationException]
      result.left.toOption.get.getMessage must include("Download URLs can only be fetched for custom video sites")
    }
  }

  "VideoAnalysisServiceImpl.videoDurationFromPath" should "parse valid duration output" in runIO {
    val service = new VideoAnalysisServiceImpl[IO, IO](
      new TestHashingService,
      new TestDownloadService(null),
      new TestYouTubeVideoDownloader(IO.raiseError(new Exception("Should not be called"))),
      Client.fromHttpApp[IO](HttpApp.liftF(IO.pure(Response[IO](Status.Ok)))),
      new TestSpaSiteRenderer,
      new TestCliCommandRunner("123.456"),
      new TestVideoMetadataDao(None),
      new TestFileResourceDao,
      storageConfiguration
    )

    service.videoDurationFromPath("/path/to/video.mp4").map { duration =>
      duration mustBe 123.seconds
    }
  }

  it should "return error for invalid duration output" in runIO {
    val service = new VideoAnalysisServiceImpl[IO, IO](
      new TestHashingService,
      new TestDownloadService(null),
      new TestYouTubeVideoDownloader(IO.raiseError(new Exception("Should not be called"))),
      Client.fromHttpApp[IO](HttpApp.liftF(IO.pure(Response[IO](Status.Ok)))),
      new TestSpaSiteRenderer,
      new TestCliCommandRunner("invalid"),
      new TestVideoMetadataDao(None),
      new TestFileResourceDao,
      storageConfiguration
    )

    service.videoDurationFromPath("/path/to/video.mp4").attempt.map { result =>
      result.isLeft mustBe true
      result.left.toOption.get mustBe a[ValidationException]
      result.left.toOption.get.getMessage must include("Unable to determine video duration")
    }
  }

  it should "return error for empty output" in runIO {
    val service = new VideoAnalysisServiceImpl[IO, IO](
      new TestHashingService,
      new TestDownloadService(null),
      new TestYouTubeVideoDownloader(IO.raiseError(new Exception("Should not be called"))),
      Client.fromHttpApp[IO](HttpApp.liftF(IO.pure(Response[IO](Status.Ok)))),
      new TestSpaSiteRenderer,
      new TestCliCommandRunner(""),
      new TestVideoMetadataDao(None),
      new TestFileResourceDao,
      storageConfiguration
    )

    service.videoDurationFromPath("/path/to/video.mp4").attempt.map { result =>
      result.isLeft mustBe true
      result.left.toOption.get mustBe a[ValidationException]
    }
  }

  it should "handle zero duration" in runIO {
    val service = new VideoAnalysisServiceImpl[IO, IO](
      new TestHashingService,
      new TestDownloadService(null),
      new TestYouTubeVideoDownloader(IO.raiseError(new Exception("Should not be called"))),
      Client.fromHttpApp[IO](HttpApp.liftF(IO.pure(Response[IO](Status.Ok)))),
      new TestSpaSiteRenderer,
      new TestCliCommandRunner("0.0"),
      new TestVideoMetadataDao(None),
      new TestFileResourceDao,
      storageConfiguration
    )

    service.videoDurationFromPath("/path/to/video.mp4").map { duration =>
      duration mustBe 0.seconds
    }
  }

  it should "floor fractional seconds" in runIO {
    val service = new VideoAnalysisServiceImpl[IO, IO](
      new TestHashingService,
      new TestDownloadService(null),
      new TestYouTubeVideoDownloader(IO.raiseError(new Exception("Should not be called"))),
      Client.fromHttpApp[IO](HttpApp.liftF(IO.pure(Response[IO](Status.Ok)))),
      new TestSpaSiteRenderer,
      new TestCliCommandRunner("59.99"),
      new TestVideoMetadataDao(None),
      new TestFileResourceDao,
      storageConfiguration
    )

    service.videoDurationFromPath("/path/to/video.mp4").map { duration =>
      duration mustBe 59.seconds
    }
  }
}
