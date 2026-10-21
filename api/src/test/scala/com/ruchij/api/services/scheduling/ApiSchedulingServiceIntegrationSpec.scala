package com.ruchij.api.services.scheduling

import cats.effect.IO
import cats.implicits._
import cats.~>
import com.ruchij.api.daos.user.DoobieUserDao
import com.ruchij.api.daos.user.models.{Email, Role, User}
import com.ruchij.api.exceptions.AuthorizationException
import com.ruchij.api.services.config.models.ApiConfigKey
import com.ruchij.api.services.config.models.ApiConfigKey.ApiConfigKeySpace
import com.ruchij.api.services.fallback.FallbackSyncRequester
import com.ruchij.api.services.fallback.FallbackSyncStubs.RecordingPublisher
import com.ruchij.api.services.scheduling.models.ScheduledVideoDeletion
import com.ruchij.api.test.data.ApiTestData
import com.ruchij.core.daos.permission.DoobieVideoPermissionDao
import com.ruchij.core.daos.permission.models.VideoPermission
import com.ruchij.core.daos.scheduling.DoobieSchedulingDao
import com.ruchij.core.daos.scheduling.models.{ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.daos.title.DoobieVideoTitleDao
import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.exceptions.ValidationException
import com.ruchij.core.external.CoreResourcesProvider
import com.ruchij.core.external.containers.ContainerCoreResourcesProvider
import com.ruchij.core.external.embedded.EmbeddedCoreResourcesProvider
import com.ruchij.core.kv.{InMemoryKeyValueStore, KeySpacedKeyValueStore}
import com.ruchij.core.services.config.ConfigurationServiceImpl
import com.ruchij.core.services.scheduling.models.WorkerStatusUpdate
import com.ruchij.core.services.video.VideoAnalysisService
import com.ruchij.core.test.IOSupport.{IOWrapper, runIO}
import com.ruchij.core.test.data.CoreTestData
import doobie.free.connection.ConnectionIO
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.ExecutionContext.Implicits.global

/** Runs ApiSchedulingServiceImpl against real DAOs, on both H2 (PostgreSQL mode) and PostgreSQL. */
class ApiSchedulingServiceIntegrationSpec extends AnyFlatSpec with Matchers with MockFactory {

  private val providers: List[(String, () => CoreResourcesProvider[IO])] =
    List(
      "H2" -> (() => new EmbeddedCoreResourcesProvider[IO]),
      "PostgreSQL" -> (() => new ContainerCoreResourcesProvider[IO])
    )

  private val OtherUser: User =
    User("bob.doe", CoreTestData.Timestamp, "Bob", "Doe", Email("bob.doe@gmail.com"), Role.User)

  private val completedId = CoreTestData.YouTubeVideo.videoMetadata.id
  private val sharedId = CoreTestData.LocalVideo.videoMetadata.id
  private val ownId = CoreTestData.SpankBangVideo.videoMetadata.id

  private val AsNormalUser = Some(ApiTestData.NormalUser.id)

  private def scheduledVideo(video: Video, status: SchedulingStatus): ScheduledVideoDownload =
    ScheduledVideoDownload(
      CoreTestData.Timestamp,
      CoreTestData.Timestamp,
      status,
      0,
      video.videoMetadata,
      None,
      None
    )

  private def permission(videoId: String, user: User): VideoPermission =
    VideoPermission(CoreTestData.Timestamp, videoId, user.id)

  // The normal user has the completed video and the shared video, which the other user has too, and their own video
  private val setUpData: ConnectionIO[Unit] =
    ApiTestData.setUpData *>
      DoobieUserDao.insert(OtherUser) *>
      List(
        scheduledVideo(CoreTestData.YouTubeVideo, SchedulingStatus.Completed),
        scheduledVideo(CoreTestData.LocalVideo, SchedulingStatus.Active),
        scheduledVideo(CoreTestData.SpankBangVideo, SchedulingStatus.Active)
      ).traverse(DoobieSchedulingDao.insert) *>
      List(
        permission(completedId, ApiTestData.NormalUser),
        permission(sharedId, ApiTestData.NormalUser),
        permission(sharedId, OtherUser),
        permission(ownId, ApiTestData.NormalUser)
      ).traverse(DoobieVideoPermissionDao.insert).void

  private case class Fixture(
    service: ApiSchedulingServiceImpl[IO, ConnectionIO],
    scheduledVideoDownloads: RecordingPublisher[ScheduledVideoDownload],
    scheduledVideoDeletions: RecordingPublisher[ScheduledVideoDeletion],
    transaction: ConnectionIO ~> IO
  ) {
    def status(videoId: String): IO[Option[SchedulingStatus]] =
      transaction(DoobieSchedulingDao.getById(videoId, None)).map(_.map(_.status))
  }

  private def runTest(provider: () => CoreResourcesProvider[IO])(test: Fixture => IO[Any]): Unit =
    runIO {
      provider().transactor.use { implicit transaction =>
        for {
          _ <- transaction(setUpData)
          scheduledVideoDownloads <- RecordingPublisher[ScheduledVideoDownload]
          scheduledVideoDeletions <- RecordingPublisher[ScheduledVideoDeletion]
          workerStatusUpdates <- RecordingPublisher[WorkerStatusUpdate]
          service = new ApiSchedulingServiceImpl[IO, ConnectionIO](
            mock[VideoAnalysisService[IO]],
            scheduledVideoDownloads,
            scheduledVideoDeletions,
            workerStatusUpdates,
            FallbackSyncRequester.noOp[IO],
            new ConfigurationServiceImpl[IO, ApiConfigKey](
              new KeySpacedKeyValueStore[IO, ApiConfigKey[_], String](ApiConfigKeySpace, new InMemoryKeyValueStore[IO])
            ),
            DoobieSchedulingDao,
            DoobieVideoTitleDao,
            DoobieVideoPermissionDao
          )
          _ <- test(Fixture(service, scheduledVideoDownloads, scheduledVideoDeletions, transaction))
        } yield ()
      }
    }

  providers.foreach {
    case (databaseName, provider) =>
      s"updateSchedulingStatus ($databaseName)" should "not queue a completed video again" in runTest(provider) {
        fixture =>
          for {
            error <- fixture.service.updateSchedulingStatus(completedId, SchedulingStatus.Queued, AsNormalUser).error
            adminError <- fixture.service.updateSchedulingStatus(completedId, SchedulingStatus.Queued, None).error
            status <- fixture.status(completedId)
            published <- fixture.scheduledVideoDownloads.messages
          } yield {
            error mustBe a[ValidationException]
            adminError mustBe a[ValidationException]
            status mustBe Some(SchedulingStatus.Completed)
            published mustBe empty
          }
      }

      it should "not let a user pause a video that another user is waiting for" in runTest(provider) { fixture =>
        for {
          error <- fixture.service.updateSchedulingStatus(sharedId, SchedulingStatus.Paused, AsNormalUser).error
          status <- fixture.status(sharedId)
          published <- fixture.scheduledVideoDownloads.messages
        } yield {
          error mustBe an[AuthorizationException]
          status mustBe Some(SchedulingStatus.Active)
          published mustBe empty
        }
      }

      it should "let a user pause and resume a video that only they are waiting for" in runTest(provider) { fixture =>
        for {
          paused <- fixture.service.updateSchedulingStatus(ownId, SchedulingStatus.Paused, AsNormalUser)
          resumed <- fixture.service.updateSchedulingStatus(ownId, SchedulingStatus.Queued, AsNormalUser)
          published <- fixture.scheduledVideoDownloads.messages
        } yield {
          paused.status mustBe SchedulingStatus.Paused
          resumed.status mustBe SchedulingStatus.Queued
          published.map(_.status) mustBe List(SchedulingStatus.Paused, SchedulingStatus.Queued)
        }
      }

      it should "let an admin pause a shared video, and any of its users resume it" in runTest(provider) { fixture =>
        for {
          paused <- fixture.service.updateSchedulingStatus(sharedId, SchedulingStatus.Paused, None)
          resumed <- fixture.service.updateSchedulingStatus(sharedId, SchedulingStatus.Queued, Some(OtherUser.id))
        } yield {
          paused.status mustBe SchedulingStatus.Paused
          resumed.status mustBe SchedulingStatus.Queued
        }
      }

      it should "not resume a video that is not paused or errored" in runTest(provider) { fixture =>
        for {
          error <- fixture.service.updateSchedulingStatus(ownId, SchedulingStatus.Queued, AsNormalUser).error
          status <- fixture.status(ownId)
        } yield {
          error mustBe a[ValidationException]
          status mustBe Some(SchedulingStatus.Active)
        }
      }

      s"deleteById ($databaseName)" should "name the video's users in an admin's deletion" in runTest(provider) { fixture =>
        for {
          deleted <- fixture.service.deleteById(sharedId, None)
          published <- fixture.scheduledVideoDownloads.messages
          deletions <- fixture.scheduledVideoDeletions.messages
          permissions <- fixture.transaction(DoobieVideoPermissionDao.find(None, Some(sharedId)))
        } yield {
          deleted.status mustBe SchedulingStatus.Deleted
          published mustBe List(deleted)
          deletions mustBe List(ScheduledVideoDeletion(deleted, List(ApiTestData.NormalUser.id, OtherUser.id).sorted))
          permissions mustBe empty
        }
      }

      it should "not publish a deletion when a user removes the video from their own downloads" in runTest(provider) {
        fixture =>
          for {
            _ <- fixture.service.deleteById(sharedId, AsNormalUser)
            published <- fixture.scheduledVideoDownloads.messages
            deletions <- fixture.scheduledVideoDeletions.messages
            permissions <- fixture.transaction(DoobieVideoPermissionDao.find(None, Some(sharedId)))
          } yield {
            published mustBe empty
            deletions mustBe empty
            permissions.map(_.userId) mustBe List(OtherUser.id)
          }
      }
  }
}
