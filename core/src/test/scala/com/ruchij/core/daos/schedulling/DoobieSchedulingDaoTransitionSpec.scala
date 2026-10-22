package com.ruchij.core.daos.schedulling

import cats.data.NonEmptyList
import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Resource}
import cats.~>
import com.ruchij.core.daos.doobie.DoobieCustomMappings._
import com.ruchij.core.daos.doobie.DoobieTransactor
import com.ruchij.core.daos.permission.DoobieVideoPermissionDao
import com.ruchij.core.daos.permission.models.VideoPermission
import com.ruchij.core.daos.resource.DoobieFileResourceDao
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.scheduling.DoobieSchedulingDao
import com.ruchij.core.daos.scheduling.models.{ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.daos.videometadata.DoobieVideoMetadataDao
import com.ruchij.core.daos.videometadata.models.{CustomVideoSite, VideoMetadata}
import com.ruchij.core.external.containers.PostgresContainer
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.types.TimeUtils
import com.ruchij.migration.MigrationApp
import com.ruchij.migration.config.{AdminConfiguration, DatabaseConfiguration, MigrationServiceConfiguration}
import doobie.ConnectionIO
import doobie.implicits._
import org.http4s.MediaType
import org.http4s.implicits.http4sLiteralsSyntax
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.{BeforeAndAfterAll, OptionValues}

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._

class PostgresDoobieSchedulingDaoTransitionSpec extends DoobieSchedulingDaoTransitionSpec(PostgresContainer.create[IO])

class H2DoobieSchedulingDaoTransitionSpec
    extends DoobieSchedulingDaoTransitionSpec(
      Resource.eval(IO.delay(UUID.randomUUID().toString.take(8))).map { databaseName =>
        DatabaseConfiguration(
          s"jdbc:h2:mem:scheduling-transition-$databaseName;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
          "",
          ""
        )
      }
    )

abstract class DoobieSchedulingDaoTransitionSpec(databaseConfiguration: Resource[IO, DatabaseConfiguration])
    extends AnyFlatSpec
    with Matchers
    with OptionValues
    with BeforeAndAfterAll {

  private val ScheduledAt: Instant = TimeUtils.instantOf(2024, 5, 1, 10, 0)
  private val TransitionedAt: Instant = TimeUtils.instantOf(2024, 5, 1, 12, 30)

  // One database per suite: every test uses its own video and user ids, so tests cannot see each other's rows
  private var transactionAndRelease: (ConnectionIO ~> IO, IO[Unit]) = _

  private def transaction: ConnectionIO ~> IO = transactionAndRelease._1

  override protected def beforeAll(): Unit =
    transactionAndRelease =
      databaseConfiguration
        .evalTap { dbConfig =>
          MigrationApp.migration[IO](MigrationServiceConfiguration(dbConfig, AdminConfiguration("dummy-hash")))
        }
        .flatMap(DoobieTransactor.create[IO])
        .map(_.trans)
        .allocated
        .unsafeRunSync()

  override protected def afterAll(): Unit =
    Option(transactionAndRelease).foreach { case (_, release) => release.unsafeRunSync() }

  private def uniqueId(prefix: String): String = s"$prefix-${UUID.randomUUID().toString.take(8)}"

  private def insertScheduledVideo(status: SchedulingStatus): IO[String] = {
    val videoId = uniqueId("video")
    val thumbnail = FileResource(s"$videoId-thumbnail", ScheduledAt, s"/images/$videoId.jpg", MediaType.image.jpeg, 100)
    val videoMetadata =
      VideoMetadata(
        uri"https://spankbang.com" / videoId,
        videoId,
        CustomVideoSite.SpankBang,
        "title",
        5.minutes,
        50_000,
        thumbnail
      )

    transaction {
      for {
        _ <- DoobieFileResourceDao.insert(thumbnail)
        _ <- DoobieVideoMetadataDao.insert(videoMetadata)
        _ <- DoobieSchedulingDao.insert(ScheduledVideoDownload(ScheduledAt, ScheduledAt, status, 0, videoMetadata, None, None))
      } yield videoId
    }
  }

  private def insertUserWithPermission(videoId: String): IO[String] = {
    val userId = uniqueId("user")

    transaction {
      sql"""
        INSERT INTO api_user (id, created_at, first_name, last_name, email, role)
          VALUES ($userId, $ScheduledAt, 'Test', 'User', ${s"$userId@ruchij.com"}, 'User')
      """.update.run
        .flatMap(_ => DoobieVideoPermissionDao.insert(VideoPermission(ScheduledAt, videoId, userId)))
        .map(_ => userId)
    }
  }

  private def transition(
    videoId: String,
    from: NonEmptyList[SchedulingStatus],
    to: SchedulingStatus,
    maybeSoleUserId: Option[String] = None
  ): IO[Option[ScheduledVideoDownload]] =
    transaction(DoobieSchedulingDao.transitionSchedulingStatusById(videoId, from, to, TransitionedAt, maybeSoleUserId))

  private def getById(videoId: String): IO[ScheduledVideoDownload] =
    transaction(DoobieSchedulingDao.getById(videoId, None)).map(_.value)

  "transitionSchedulingStatusById" should "update the status when the current status is one of the allowed sources" in runIO {
    for {
      videoId <- insertScheduledVideo(SchedulingStatus.Queued)
      result <- transition(videoId, NonEmptyList.of(SchedulingStatus.Paused, SchedulingStatus.Queued), SchedulingStatus.Paused)
      stored <- getById(videoId)
    } yield {
      result.value.status mustBe SchedulingStatus.Paused
      result.value.lastUpdatedAt.toEpochMilli mustBe TransitionedAt.toEpochMilli
      stored.status mustBe SchedulingStatus.Paused
      stored.lastUpdatedAt.toEpochMilli mustBe TransitionedAt.toEpochMilli
    }
  }

  it should "leave the row untouched and return None when the current status is not an allowed source" in runIO {
    for {
      videoId <- insertScheduledVideo(SchedulingStatus.Completed)
      result <- transition(videoId, NonEmptyList.of(SchedulingStatus.Queued, SchedulingStatus.Active), SchedulingStatus.Paused)
      stored <- getById(videoId)
    } yield {
      result mustBe None
      stored.status mustBe SchedulingStatus.Completed
      stored.lastUpdatedAt.toEpochMilli mustBe ScheduledAt.toEpochMilli
    }
  }

  it should "return None for a video that does not exist" in runIO {
    transition(uniqueId("missing"), NonEmptyList.of(SchedulingStatus.Queued), SchedulingStatus.Paused).map {
      _ mustBe None
    }
  }

  it should "reset the retry count and clear the error and retry time when leaving Error" in runIO {
    for {
      videoId <- insertScheduledVideo(SchedulingStatus.Queued)
      _ <- transaction {
        DoobieSchedulingDao.scheduleRetry(videoId, new Exception("first"), ScheduledAt.plusSeconds(60), ScheduledAt)
      }
      _ <- transaction {
        DoobieSchedulingDao.scheduleRetry(videoId, new Exception("second"), ScheduledAt.plusSeconds(120), ScheduledAt.plusSeconds(1))
      }
      errored <- transaction(DoobieSchedulingDao.setErrorById(videoId, new Exception("final"), ScheduledAt.plusSeconds(2)))
      result <- transition(videoId, NonEmptyList.of(SchedulingStatus.Error), SchedulingStatus.Queued)
    } yield {
      errored.value.status mustBe SchedulingStatus.Error
      errored.value.retryCount mustBe 2
      errored.value.errorInfo.map(_.message) mustBe Some("final")

      result.value.status mustBe SchedulingStatus.Queued
      result.value.retryCount mustBe 0
      result.value.errorInfo mustBe None
      result.value.retryAfter mustBe None
    }
  }

  it should "keep the retry count when leaving a status other than Error" in runIO {
    for {
      videoId <- insertScheduledVideo(SchedulingStatus.Queued)
      retrying <- transaction {
        DoobieSchedulingDao.scheduleRetry(videoId, new Exception("failed"), ScheduledAt.plusSeconds(60), ScheduledAt)
      }
      result <- transition(videoId, NonEmptyList.of(SchedulingStatus.Queued), SchedulingStatus.Paused)
    } yield {
      retrying.value.retryCount mustBe 1
      retrying.value.retryAfter mustBe defined

      result.value.status mustBe SchedulingStatus.Paused
      result.value.retryCount mustBe 1
      result.value.errorInfo mustBe None
      result.value.retryAfter mustBe None
    }
  }

  it should "update a video whose only permission belongs to the sole user" in runIO {
    for {
      videoId <- insertScheduledVideo(SchedulingStatus.Queued)
      userId <- insertUserWithPermission(videoId)
      result <- transition(videoId, NonEmptyList.of(SchedulingStatus.Queued), SchedulingStatus.Paused, Some(userId))
    } yield result.value.status mustBe SchedulingStatus.Paused
  }

  it should "update a video that has no permissions when a sole user is given" in runIO {
    for {
      videoId <- insertScheduledVideo(SchedulingStatus.Queued)
      result <-
        transition(videoId, NonEmptyList.of(SchedulingStatus.Queued), SchedulingStatus.Paused, Some(uniqueId("user")))
    } yield result.value.status mustBe SchedulingStatus.Paused
  }

  it should "not update a video that another user also has permission for" in runIO {
    for {
      videoId <- insertScheduledVideo(SchedulingStatus.Queued)
      userId <- insertUserWithPermission(videoId)
      _ <- insertUserWithPermission(videoId)
      asSoleUser <- transition(videoId, NonEmptyList.of(SchedulingStatus.Queued), SchedulingStatus.Paused, Some(userId))
      storedAfterSoleUser <- getById(videoId)
      withoutSoleUser <- transition(videoId, NonEmptyList.of(SchedulingStatus.Queued), SchedulingStatus.Paused)
    } yield {
      asSoleUser mustBe None
      storedAfterSoleUser.status mustBe SchedulingStatus.Queued

      // Without a sole user, as for an admin, the permission check does not apply
      withoutSoleUser.value.status mustBe SchedulingStatus.Paused
    }
  }
}
