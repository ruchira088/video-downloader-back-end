package com.ruchij.core.daos.permission

import cats.effect.IO
import cats.~>
import com.ruchij.core.daos.permission.models.VideoPermission
import com.ruchij.core.daos.resource.DoobieFileResourceDao
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.scheduling.DoobieSchedulingDao
import com.ruchij.core.daos.scheduling.models.{ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.daos.videometadata.DoobieVideoMetadataDao
import com.ruchij.core.daos.videometadata.models.{CustomVideoSite, VideoMetadata}
import com.ruchij.core.external.embedded.EmbeddedCoreResourcesProvider
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.types.Clock
import doobie.ConnectionIO
import org.http4s.MediaType
import org.http4s.implicits.http4sLiteralsSyntax
import java.time.Instant
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps
import doobie.implicits._
import com.ruchij.core.daos.doobie.DoobieCustomMappings._

class DoobieVideoPermissionDaoSpec extends AnyFlatSpec with Matchers with OptionValues {

  private def insertTestUser(userId: String, email: String, timestamp: Instant): ConnectionIO[Int] =
    sql"""
      INSERT INTO api_user (id, created_at, first_name, last_name, email, role)
        VALUES ($userId, $timestamp, 'Test', 'User', $email, 'User')
    """.update.run

  def runTest(testFn: (ScheduledVideoDownload, ConnectionIO ~> IO) => IO[Unit]): Unit =
    runIO {
      new EmbeddedCoreResourcesProvider[IO].transactor.use { transaction =>
        for {
          timestamp <- Clock[IO].timestamp
          thumbnailFileResource = FileResource("thumbnail-id", timestamp, "/opt/image/thumbnail.jpg", MediaType.image.jpeg, 100)
          _ <- transaction {
            DoobieFileResourceDao.insert(thumbnailFileResource)
          }

          videoMetadata =
            VideoMetadata(
              uri"https://example.com/video",
              "video-metadata-id",
              CustomVideoSite.SpankBang,
              "sample-video-title",
              5 minutes,
              50_000,
              thumbnailFileResource
            )
          _ <- transaction {
            DoobieVideoMetadataDao.insert(videoMetadata)
          }

          scheduledVideoDownload =
            ScheduledVideoDownload(
              timestamp,
              timestamp,
              SchedulingStatus.Queued,
              0,
              videoMetadata,
              None,
              None
            )
          _ <- transaction {
            DoobieSchedulingDao.insert(scheduledVideoDownload)
          }

          result <- testFn(scheduledVideoDownload, transaction)
        } yield result
      }
    }

  "DoobieVideoPermissionDao" should "insert a video permission" in runTest {
    (scheduledVideoDownload, transaction) =>
      for {
        timestamp <- Clock[IO].timestamp
        _ <- transaction { insertTestUser("user-123", "user123@test.com", timestamp) }
        videoPermission = VideoPermission(timestamp, scheduledVideoDownload.videoMetadata.id, "user-123")

        insertResult <- transaction {
          DoobieVideoPermissionDao.insert(videoPermission)
        }

        _ <- IO.delay {
          insertResult mustBe 1
        }
      } yield (): Unit
  }

  it should "find permissions by user ID" in runTest {
    (scheduledVideoDownload, transaction) =>
      for {
        timestamp <- Clock[IO].timestamp
        _ <- transaction { insertTestUser("user-123", "user123@test.com", timestamp) }
        videoPermission = VideoPermission(timestamp, scheduledVideoDownload.videoMetadata.id, "user-123")

        _ <- transaction {
          DoobieVideoPermissionDao.insert(videoPermission)
        }

        foundPermissions <- transaction {
          DoobieVideoPermissionDao.find(Some("user-123"), None)
        }

        _ <- IO.delay {
          foundPermissions.size mustBe 1
          foundPermissions.head.userId mustBe "user-123"
          foundPermissions.head.scheduledVideoDownloadId mustBe scheduledVideoDownload.videoMetadata.id
          foundPermissions.head.grantedAt.toEpochMilli mustBe timestamp.toEpochMilli
        }
      } yield (): Unit
  }

  it should "find permissions by scheduled video ID" in runTest {
    (scheduledVideoDownload, transaction) =>
      for {
        timestamp <- Clock[IO].timestamp
        _ <- transaction { insertTestUser("user-456", "user456@test.com", timestamp) }
        videoPermission = VideoPermission(timestamp, scheduledVideoDownload.videoMetadata.id, "user-456")

        _ <- transaction {
          DoobieVideoPermissionDao.insert(videoPermission)
        }

        foundPermissions <- transaction {
          DoobieVideoPermissionDao.find(None, Some(scheduledVideoDownload.videoMetadata.id))
        }

        _ <- IO.delay {
          foundPermissions.size mustBe 1
          foundPermissions.head.scheduledVideoDownloadId mustBe scheduledVideoDownload.videoMetadata.id
          foundPermissions.head.userId mustBe "user-456"
        }
      } yield (): Unit
  }

  it should "find permissions by both user ID and video ID" in runTest {
    (scheduledVideoDownload, transaction) =>
      for {
        timestamp <- Clock[IO].timestamp
        _ <- transaction { insertTestUser("user-789", "user789@test.com", timestamp) }
        videoPermission = VideoPermission(timestamp, scheduledVideoDownload.videoMetadata.id, "user-789")

        _ <- transaction {
          DoobieVideoPermissionDao.insert(videoPermission)
        }

        foundPermissions <- transaction {
          DoobieVideoPermissionDao.find(Some("user-789"), Some(scheduledVideoDownload.videoMetadata.id))
        }

        _ <- IO.delay {
          foundPermissions.size mustBe 1
          foundPermissions.head.userId mustBe "user-789"
          foundPermissions.head.scheduledVideoDownloadId mustBe scheduledVideoDownload.videoMetadata.id
        }
      } yield (): Unit
  }

  it should "find all permissions when no filters provided" in runTest {
    (scheduledVideoDownload, transaction) =>
      for {
        timestamp <- Clock[IO].timestamp
        _ <- transaction { insertTestUser("user-1", "user1@test.com", timestamp) }
        _ <- transaction { insertTestUser("user-2", "user2@test.com", timestamp) }
        permission1 = VideoPermission(timestamp, scheduledVideoDownload.videoMetadata.id, "user-1")
        permission2 = VideoPermission(timestamp, scheduledVideoDownload.videoMetadata.id, "user-2")

        _ <- transaction {
          DoobieVideoPermissionDao.insert(permission1)
        }
        _ <- transaction {
          DoobieVideoPermissionDao.insert(permission2)
        }

        allPermissions <- transaction {
          DoobieVideoPermissionDao.find(None, None)
        }

        _ <- IO.delay {
          allPermissions.size mustBe 2
          allPermissions.map(_.userId).toSet mustBe Set("user-1", "user-2")
        }
      } yield (): Unit
  }

  it should "return empty when no permissions match user ID" in runTest {
    (scheduledVideoDownload, transaction) =>
      for {
        timestamp <- Clock[IO].timestamp
        _ <- transaction { insertTestUser("user-123", "user123@test.com", timestamp) }
        videoPermission = VideoPermission(timestamp, scheduledVideoDownload.videoMetadata.id, "user-123")

        _ <- transaction {
          DoobieVideoPermissionDao.insert(videoPermission)
        }

        foundPermissions <- transaction {
          DoobieVideoPermissionDao.find(Some("non-existent-user"), None)
        }

        _ <- IO.delay {
          foundPermissions mustBe empty
        }
      } yield (): Unit
  }

  it should "return empty when no permissions match video ID" in runTest {
    (scheduledVideoDownload, transaction) =>
      for {
        timestamp <- Clock[IO].timestamp
        _ <- transaction { insertTestUser("user-123", "user123b@test.com", timestamp) }
        videoPermission = VideoPermission(timestamp, scheduledVideoDownload.videoMetadata.id, "user-123")

        _ <- transaction {
          DoobieVideoPermissionDao.insert(videoPermission)
        }

        foundPermissions <- transaction {
          DoobieVideoPermissionDao.find(None, Some("non-existent-video"))
        }

        _ <- IO.delay {
          foundPermissions mustBe empty
        }
      } yield (): Unit
  }

  it should "delete permissions by user ID" in runTest {
    (scheduledVideoDownload, transaction) =>
      for {
        timestamp <- Clock[IO].timestamp
        _ <- transaction { insertTestUser("user-to-delete", "usertodelete@test.com", timestamp) }
        videoPermission = VideoPermission(timestamp, scheduledVideoDownload.videoMetadata.id, "user-to-delete")

        _ <- transaction {
          DoobieVideoPermissionDao.insert(videoPermission)
        }

        deleteResult <- transaction {
          DoobieVideoPermissionDao.delete(Some("user-to-delete"), None)
        }

        _ <- IO.delay {
          deleteResult mustBe 1
        }

        foundAfterDelete <- transaction {
          DoobieVideoPermissionDao.find(Some("user-to-delete"), None)
        }

        _ <- IO.delay {
          foundAfterDelete mustBe empty
        }
      } yield (): Unit
  }

  it should "delete permissions by video ID" in runTest {
    (scheduledVideoDownload, transaction) =>
      for {
        timestamp <- Clock[IO].timestamp
        _ <- transaction { insertTestUser("user-123", "user123c@test.com", timestamp) }
        videoPermission = VideoPermission(timestamp, scheduledVideoDownload.videoMetadata.id, "user-123")

        _ <- transaction {
          DoobieVideoPermissionDao.insert(videoPermission)
        }

        deleteResult <- transaction {
          DoobieVideoPermissionDao.delete(None, Some(scheduledVideoDownload.videoMetadata.id))
        }

        _ <- IO.delay {
          deleteResult mustBe 1
        }

        foundAfterDelete <- transaction {
          DoobieVideoPermissionDao.find(None, Some(scheduledVideoDownload.videoMetadata.id))
        }

        _ <- IO.delay {
          foundAfterDelete mustBe empty
        }
      } yield (): Unit
  }

  it should "delete permissions by both user ID and video ID" in runTest {
    (scheduledVideoDownload, transaction) =>
      for {
        timestamp <- Clock[IO].timestamp
        _ <- transaction { insertTestUser("user-A", "userA@test.com", timestamp) }
        _ <- transaction { insertTestUser("user-B", "userB@test.com", timestamp) }
        permission1 = VideoPermission(timestamp, scheduledVideoDownload.videoMetadata.id, "user-A")
        permission2 = VideoPermission(timestamp, scheduledVideoDownload.videoMetadata.id, "user-B")

        _ <- transaction {
          DoobieVideoPermissionDao.insert(permission1)
        }
        _ <- transaction {
          DoobieVideoPermissionDao.insert(permission2)
        }

        deleteResult <- transaction {
          DoobieVideoPermissionDao.delete(Some("user-A"), Some(scheduledVideoDownload.videoMetadata.id))
        }

        _ <- IO.delay {
          deleteResult mustBe 1
        }

        remainingPermissions <- transaction {
          DoobieVideoPermissionDao.find(None, Some(scheduledVideoDownload.videoMetadata.id))
        }

        _ <- IO.delay {
          remainingPermissions.size mustBe 1
          remainingPermissions.head.userId mustBe "user-B"
        }
      } yield (): Unit
  }

  it should "return 0 when deleting non-existent permission" in runTest {
    (_, transaction) =>
      for {
        deleteResult <- transaction {
          DoobieVideoPermissionDao.delete(Some("non-existent-user"), None)
        }

        _ <- IO.delay {
          deleteResult mustBe 0
        }
      } yield (): Unit
  }

  it should "fail when both userId and scheduledVideoId are empty for delete" in runTest {
    (_, transaction) =>
      for {
        result <- transaction {
          DoobieVideoPermissionDao.delete(None, None)
        }.attempt

        _ <- IO.delay {
          result.isLeft mustBe true
          result.left.exists(_.isInstanceOf[IllegalArgumentException]) mustBe true
          result.left.exists(_.getMessage.contains("Both userId and scheduledVideoId cannot be empty")) mustBe true
        }
      } yield (): Unit
  }

  it should "insert multiple permissions for different users on the same video" in runTest {
    (scheduledVideoDownload, transaction) =>
      for {
        timestamp <- Clock[IO].timestamp
        _ <- transaction { insertTestUser("user-1", "user1b@test.com", timestamp) }
        _ <- transaction { insertTestUser("user-2", "user2b@test.com", timestamp) }
        _ <- transaction { insertTestUser("user-3", "user3@test.com", timestamp) }
        permission1 = VideoPermission(timestamp, scheduledVideoDownload.videoMetadata.id, "user-1")
        permission2 = VideoPermission(timestamp.plusSeconds(1), scheduledVideoDownload.videoMetadata.id, "user-2")
        permission3 = VideoPermission(timestamp.plusSeconds(2), scheduledVideoDownload.videoMetadata.id, "user-3")

        insertResult1 <- transaction {
          DoobieVideoPermissionDao.insert(permission1)
        }
        insertResult2 <- transaction {
          DoobieVideoPermissionDao.insert(permission2)
        }
        insertResult3 <- transaction {
          DoobieVideoPermissionDao.insert(permission3)
        }

        _ <- IO.delay {
          insertResult1 mustBe 1
          insertResult2 mustBe 1
          insertResult3 mustBe 1
        }

        allPermissions <- transaction {
          DoobieVideoPermissionDao.find(None, Some(scheduledVideoDownload.videoMetadata.id))
        }

        _ <- IO.delay {
          allPermissions.size mustBe 3
          allPermissions.map(_.userId).toSet mustBe Set("user-1", "user-2", "user-3")
        }
      } yield (): Unit
  }

  it should "delete all permissions for a video when only video ID is provided" in runTest {
    (scheduledVideoDownload, transaction) =>
      for {
        timestamp <- Clock[IO].timestamp
        _ <- transaction { insertTestUser("user-1", "user1c@test.com", timestamp) }
        _ <- transaction { insertTestUser("user-2", "user2c@test.com", timestamp) }
        permission1 = VideoPermission(timestamp, scheduledVideoDownload.videoMetadata.id, "user-1")
        permission2 = VideoPermission(timestamp.plusSeconds(1), scheduledVideoDownload.videoMetadata.id, "user-2")

        _ <- transaction {
          DoobieVideoPermissionDao.insert(permission1)
        }
        _ <- transaction {
          DoobieVideoPermissionDao.insert(permission2)
        }

        deleteResult <- transaction {
          DoobieVideoPermissionDao.delete(None, Some(scheduledVideoDownload.videoMetadata.id))
        }

        _ <- IO.delay {
          deleteResult mustBe 2
        }

        remainingPermissions <- transaction {
          DoobieVideoPermissionDao.find(None, Some(scheduledVideoDownload.videoMetadata.id))
        }

        _ <- IO.delay {
          remainingPermissions mustBe empty
        }
      } yield (): Unit
  }

  it should "preserve permission data correctly after insert and retrieve" in runTest {
    (scheduledVideoDownload, transaction) =>
      for {
        timestamp <- Clock[IO].timestamp
        _ <- transaction { insertTestUser("detailed-user", "detailed@test.com", timestamp) }
        videoPermission = VideoPermission(timestamp, scheduledVideoDownload.videoMetadata.id, "detailed-user")

        _ <- transaction {
          DoobieVideoPermissionDao.insert(videoPermission)
        }

        foundPermissions <- transaction {
          DoobieVideoPermissionDao.find(Some("detailed-user"), Some(scheduledVideoDownload.videoMetadata.id))
        }

        _ <- IO.delay {
          foundPermissions.size mustBe 1
          val found = foundPermissions.head
          found.grantedAt.toEpochMilli mustBe timestamp.toEpochMilli
          found.scheduledVideoDownloadId mustBe scheduledVideoDownload.videoMetadata.id
          found.userId mustBe "detailed-user"
        }
      } yield (): Unit
  }

  it should "handle special characters in user ID" in runTest {
    (scheduledVideoDownload, transaction) =>
      for {
        timestamp <- Clock[IO].timestamp
        specialUserId = "user@example.com"
        _ <- transaction { insertTestUser(specialUserId, "special@test.com", timestamp) }
        videoPermission = VideoPermission(timestamp, scheduledVideoDownload.videoMetadata.id, specialUserId)

        insertResult <- transaction {
          DoobieVideoPermissionDao.insert(videoPermission)
        }

        _ <- IO.delay {
          insertResult mustBe 1
        }

        foundPermissions <- transaction {
          DoobieVideoPermissionDao.find(Some(specialUserId), None)
        }

        _ <- IO.delay {
          foundPermissions.size mustBe 1
          foundPermissions.head.userId mustBe specialUserId
        }
      } yield (): Unit
  }

  it should "find permissions for user across multiple videos" in runTest {
    (scheduledVideoDownload, transaction) =>
      for {
        timestamp <- Clock[IO].timestamp
        _ <- transaction { insertTestUser("multi-video-user", "multivideo@test.com", timestamp) }

        thumbnailFileResource2 = FileResource("thumbnail-id-2", timestamp, "/opt/image/thumbnail2.jpg", MediaType.image.jpeg, 100)
        _ <- transaction {
          DoobieFileResourceDao.insert(thumbnailFileResource2)
        }

        videoMetadata2 =
          VideoMetadata(
            uri"https://example.com/video2",
            "video-metadata-id-2",
            CustomVideoSite.SpankBang,
            "sample-video-title-2",
            10 minutes,
            100_000,
            thumbnailFileResource2
          )
        _ <- transaction {
          DoobieVideoMetadataDao.insert(videoMetadata2)
        }

        scheduledVideoDownload2 =
          ScheduledVideoDownload(
            timestamp,
            timestamp,
            SchedulingStatus.Queued,
            0,
            videoMetadata2,
            None,
            None
          )
        _ <- transaction {
          DoobieSchedulingDao.insert(scheduledVideoDownload2)
        }

        permission1 = VideoPermission(timestamp, scheduledVideoDownload.videoMetadata.id, "multi-video-user")
        permission2 = VideoPermission(timestamp.plusSeconds(1), scheduledVideoDownload2.videoMetadata.id, "multi-video-user")

        _ <- transaction {
          DoobieVideoPermissionDao.insert(permission1)
        }
        _ <- transaction {
          DoobieVideoPermissionDao.insert(permission2)
        }

        foundPermissions <- transaction {
          DoobieVideoPermissionDao.find(Some("multi-video-user"), None)
        }

        _ <- IO.delay {
          foundPermissions.size mustBe 2
          foundPermissions.map(_.scheduledVideoDownloadId).toSet mustBe Set(
            scheduledVideoDownload.videoMetadata.id,
            scheduledVideoDownload2.videoMetadata.id
          )
        }
      } yield (): Unit
  }

  it should "return empty sequence when database has no permissions" in runTest {
    (_, transaction) =>
      for {
        foundPermissions <- transaction {
          DoobieVideoPermissionDao.find(None, None)
        }

        _ <- IO.delay {
          foundPermissions mustBe empty
        }
      } yield (): Unit
  }

  it should "handle permissions with different timestamps correctly" in runTest {
    (scheduledVideoDownload, transaction) =>
      for {
        timestamp <- Clock[IO].timestamp
        olderTimestamp = timestamp.minus(java.time.Duration.ofDays(1))
        newerTimestamp = timestamp.plus(java.time.Duration.ofDays(1))
        _ <- transaction { insertTestUser("user-old", "userold@test.com", timestamp) }
        _ <- transaction { insertTestUser("user-new", "usernew@test.com", timestamp) }

        permission1 = VideoPermission(olderTimestamp, scheduledVideoDownload.videoMetadata.id, "user-old")
        permission2 = VideoPermission(newerTimestamp, scheduledVideoDownload.videoMetadata.id, "user-new")

        _ <- transaction {
          DoobieVideoPermissionDao.insert(permission1)
        }
        _ <- transaction {
          DoobieVideoPermissionDao.insert(permission2)
        }

        foundPermissions <- transaction {
          DoobieVideoPermissionDao.find(None, Some(scheduledVideoDownload.videoMetadata.id))
        }

        _ <- IO.delay {
          foundPermissions.size mustBe 2

          val oldPermission = foundPermissions.find(_.userId == "user-old").value
          oldPermission.grantedAt.toEpochMilli mustBe olderTimestamp.toEpochMilli

          val newPermission = foundPermissions.find(_.userId == "user-new").value
          newPermission.grantedAt.toEpochMilli mustBe newerTimestamp.toEpochMilli
        }
      } yield (): Unit
  }
}
