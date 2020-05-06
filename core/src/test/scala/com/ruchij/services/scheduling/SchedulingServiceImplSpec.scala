package com.ruchij.services.scheduling

import java.util.concurrent.ConcurrentHashMap

import cats.effect.{Async, ContextShift, IO, Resource, Timer}
import cats.implicits._
import com.ruchij.config.DownloadConfiguration
import com.ruchij.daos.resource.DoobieFileResourceDao
import com.ruchij.daos.resource.models.FileResource
import com.ruchij.daos.scheduling.DoobieSchedulingDao
import com.ruchij.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.daos.videometadata.DoobieVideoMetadataDao
import com.ruchij.daos.videometadata.models.{VideoMetadata, VideoSite}
import com.ruchij.services.download.{DownloadService, Http4sDownloadService}
import com.ruchij.services.hashing.{HashingService, MurmurHash3Service}
import com.ruchij.services.repository.InMemoryRepositoryService
import com.ruchij.services.scheduling.SchedulingServiceImplSpec.{createSchedulingService, downloadConfiguration}
import com.ruchij.services.video.VideoAnalysisService
import com.ruchij.services.video.models.VideoAnalysisResult
import com.ruchij.test.utils.Providers
import com.ruchij.test.utils.Providers.{blocker, contextShift}
import fs2.Stream
import org.http4s.client.Client
import org.http4s.headers.{`Content-Length`, `Content-Type`}
import org.http4s.implicits._
import org.http4s.{MediaType, Request, Response, Uri}
import org.joda.time.DateTime
import org.scalamock.scalatest.MockFactory
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.language.postfixOps

class SchedulingServiceImplSpec extends AnyFlatSpec with Matchers with MockFactory with OptionValues {
  import ExecutionContext.Implicits.global

  "SchedulingServiceImpl" should "save the scheduled video task" in {
    val videoUrl: Uri = uri"https://www.vporn.com/caught/caught-my-bbc-roommate-spying/276979928/"

    val videoAnalysisService = mock[VideoAnalysisService[IO]]

    val videoAnalysisResult =
      VideoAnalysisResult(
        videoUrl,
        VideoSite.VPorn,
        "Caught My Bbc Roommate Spying",
        204 seconds,
        1988,
        uri"https://th-eu3.vporn.com/t/28/276979928/b81.jpg"
      )

    (videoAnalysisService.metadata _)
      .expects(videoUrl)
      .returns(IO.pure(videoAnalysisResult))

    val dateTime = DateTime.now()
    implicit val timer: Timer[IO] = Providers.stubTimer(dateTime)

    val client = mock[Client[IO]]

    (client.run _)
      .expects(argThat { request: Request[IO] => request.uri == videoAnalysisResult.thumbnail })
      .returns {
        Resource.pure[IO, Response[IO]] {
          Response[IO]()
            .withHeaders(
              `Content-Length`.unsafeFromLong(videoAnalysisResult.size),
              `Content-Type`(MediaType.image.jpeg)
            )
            .withBodyStream(Stream.emits[IO, Byte](Seq.fill(videoAnalysisResult.size.toInt)(1)))
        }
      }

    val hashingService = new MurmurHash3Service[IO](blocker)
    val repositoryService = new InMemoryRepositoryService[IO](new ConcurrentHashMap())
    val downloadService = new Http4sDownloadService[IO](client, repositoryService)

    val expectedScheduledDownloadVideo =
      ScheduledVideoDownload(
        dateTime,
        dateTime,
        false,
        VideoMetadata(
          videoAnalysisResult.url,
          hashingService.hash(videoUrl.renderString).unsafeRunSync(),
          videoAnalysisResult.videoSite,
          videoAnalysisResult.title,
          videoAnalysisResult.duration,
          videoAnalysisResult.size,
          FileResource(
            hashingService.hash(videoAnalysisResult.thumbnail.renderString).unsafeRunSync(),
            dateTime,
            s"${downloadConfiguration.imageFolder}/b81.jpg",
            MediaType.image.jpeg,
            videoAnalysisResult.size
          )
        ),
        0,
        None
      )

    val insertionResult =
      for {
        schedulingService <- createSchedulingService[IO](videoAnalysisService, hashingService, downloadService)
        scheduledVideoDownload <- schedulingService.schedule(videoUrl)
      } yield scheduledVideoDownload

    insertionResult.unsafeRunSync() mustBe expectedScheduledDownloadVideo
  }
}

object SchedulingServiceImplSpec {
  val downloadConfiguration: DownloadConfiguration =
    DownloadConfiguration(videoFolder = "videos", imageFolder = "images")

  def createSchedulingService[F[_]: Async: ContextShift: Timer](
    videoAnalysisService: VideoAnalysisService[F],
    hashingService: HashingService[F],
    downloadService: DownloadService[F]
  )(implicit executionContext: ExecutionContext): F[SchedulingService[F]] =
    Providers.h2Transactor
      .map { transactor =>
        val fileResourceDao = new DoobieFileResourceDao[F](transactor)
        val videoMetadataDao = new DoobieVideoMetadataDao[F](fileResourceDao)
        val schedulingDao = new DoobieSchedulingDao[F](videoMetadataDao, transactor)

        new SchedulingServiceImpl[F](
          videoAnalysisService,
          schedulingDao,
          hashingService,
          downloadService,
          downloadConfiguration
        )
      }
}
