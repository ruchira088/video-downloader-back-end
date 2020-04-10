package com.ruchij.services.scheduling

import cats.effect.{Async, ContextShift, IO, Timer}
import cats.implicits._
import com.ruchij.daos.doobie.DoobieTransactor
import com.ruchij.daos.scheduling.{DoobieSchedulingDao, SchedulingDao}
import com.ruchij.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.daos.videometadata.DoobieVideoMetadataDao
import com.ruchij.daos.videometadata.models.{VideoMetadata, VideoSite}
import com.ruchij.migration.MigrationApp
import com.ruchij.services.video.VideoAnalysisService
import com.ruchij.test.utils.Providers
import com.ruchij.test.utils.Providers.{blocker, contextShift, h2DatabaseConfiguration}
import org.http4s.Uri
import org.http4s.implicits._
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

    val videoMetadata =
      VideoMetadata(
        videoUrl,
        "LTg5MTk3NjIyNw",
        VideoSite.VPorn,
        "Caught My Bbc Roommate Spying",
        204 seconds,
        1988,
        uri"https://th-eu3.vporn.com/t/28/276979928/b81.jpg"
      )

    (videoAnalysisService.metadata _)
      .expects(videoUrl)
      .returns(IO.pure(videoMetadata))

    val dateTime = DateTime.now()
    implicit val timer: Timer[IO] = Providers.stubTimer(dateTime)

    val expectedScheduledDownloadVideo =
      ScheduledVideoDownload(dateTime, dateTime, false, videoMetadata, 0, None)

    val insertionResult =
      for {
        (schedulingService, _) <- SchedulingServiceImplSpec.schedulingService[IO](videoAnalysisService)
        scheduledVideoDownload <- schedulingService.schedule(videoUrl)
      } yield scheduledVideoDownload

    insertionResult.unsafeRunSync() mustBe expectedScheduledDownloadVideo

    val queryResult =
      for {
        (_, schedulingDao) <- SchedulingServiceImplSpec.schedulingService[IO](videoAnalysisService)
        scheduledVideoDownload <- schedulingDao.getByKey(videoMetadata.key).value
      }
      yield scheduledVideoDownload

    queryResult.unsafeRunSync().value mustBe expectedScheduledDownloadVideo
  }
}

object SchedulingServiceImplSpec {
  def schedulingService[F[_]: Async: ContextShift: Timer](
    videoAnalysisService: VideoAnalysisService[F]
  )(implicit executionContext: ExecutionContext): F[(SchedulingService[F], SchedulingDao[F])] =
    MigrationApp
      .migration(h2DatabaseConfiguration, blocker)
      .productR(DoobieTransactor.create[F](h2DatabaseConfiguration, blocker))
      .map { transactor =>
        val videoMetadataDao = new DoobieVideoMetadataDao[F](transactor)
        val schedulingDao = new DoobieSchedulingDao[F](videoMetadataDao, transactor)

        (new SchedulingServiceImpl[F](videoAnalysisService, schedulingDao), schedulingDao)
      }
}
