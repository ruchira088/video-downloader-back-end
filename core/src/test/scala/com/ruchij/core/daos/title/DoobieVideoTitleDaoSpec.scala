package com.ruchij.core.daos.title

import cats.effect.IO
import cats.~>
import com.ruchij.core.daos.resource.DoobieFileResourceDao
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.scheduling.DoobieSchedulingDao
import com.ruchij.core.daos.scheduling.models.{ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.daos.title.models.VideoTitle
import com.ruchij.core.daos.videometadata.DoobieVideoMetadataDao
import com.ruchij.core.daos.videometadata.models.{CustomVideoSite, VideoMetadata}
import com.ruchij.core.external.embedded.EmbeddedCoreResourcesProvider
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.types.JodaClock
import doobie.ConnectionIO
import doobie.implicits._
import org.http4s.MediaType
import org.http4s.implicits.http4sLiteralsSyntax
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps

class DoobieVideoTitleDaoSpec extends AnyFlatSpec with Matchers with OptionValues {

  private def insertTestUser(userId: String, email: String): ConnectionIO[Int] =
    sql"""
      INSERT INTO api_user (id, created_at, first_name, last_name, email, role)
        VALUES ($userId, CURRENT_TIMESTAMP, 'Test', 'User', $email, 'User')
    """.update.run

  def runTest(testFn: (ScheduledVideoDownload, ConnectionIO ~> IO) => IO[Unit]): Unit =
    runIO {
      new EmbeddedCoreResourcesProvider[IO].transactor.use { transaction =>
        for {
          timestamp <- JodaClock[IO].timestamp
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

  "DoobieVideoTitleDao" should "insert a video title" in runTest {
    (scheduledVideoDownload, transaction) =>
      for {
        _ <- transaction { insertTestUser("user-123", "user123@test.com") }

        videoTitle = VideoTitle(scheduledVideoDownload.videoMetadata.id, "user-123", "Custom Title")

        insertResult <- transaction {
          DoobieVideoTitleDao.insert(videoTitle)
        }

        _ <- IO.delay {
          insertResult mustBe 1
        }
      } yield (): Unit
  }

  it should "find video title by videoId and userId" in runTest {
    (scheduledVideoDownload, transaction) =>
      for {
        _ <- transaction { insertTestUser("user-456", "user456@test.com") }

        videoTitle = VideoTitle(scheduledVideoDownload.videoMetadata.id, "user-456", "My Custom Title")
        _ <- transaction {
          DoobieVideoTitleDao.insert(videoTitle)
        }

        foundTitle <- transaction {
          DoobieVideoTitleDao.find(scheduledVideoDownload.videoMetadata.id, "user-456")
        }

        _ <- IO.delay {
          foundTitle mustBe defined
          foundTitle.value.title mustBe "My Custom Title"
          foundTitle.value.videoId mustBe scheduledVideoDownload.videoMetadata.id
          foundTitle.value.userId mustBe "user-456"
        }
      } yield (): Unit
  }

  it should "return None when video title not found" in runTest {
    (scheduledVideoDownload, transaction) =>
      for {
        foundTitle <- transaction {
          DoobieVideoTitleDao.find(scheduledVideoDownload.videoMetadata.id, "non-existent-user")
        }

        _ <- IO.delay {
          foundTitle mustBe None
        }
      } yield (): Unit
  }

  it should "update video title" in runTest {
    (scheduledVideoDownload, transaction) =>
      for {
        _ <- transaction { insertTestUser("user-789", "user789@test.com") }

        videoTitle = VideoTitle(scheduledVideoDownload.videoMetadata.id, "user-789", "Original Title")
        _ <- transaction {
          DoobieVideoTitleDao.insert(videoTitle)
        }

        updateResult <- transaction {
          DoobieVideoTitleDao.update(scheduledVideoDownload.videoMetadata.id, "user-789", "Updated Title")
        }

        _ <- IO.delay {
          updateResult mustBe 1
        }

        foundTitle <- transaction {
          DoobieVideoTitleDao.find(scheduledVideoDownload.videoMetadata.id, "user-789")
        }

        _ <- IO.delay {
          foundTitle mustBe defined
          foundTitle.value.title mustBe "Updated Title"
        }
      } yield (): Unit
  }

  it should "return 0 when updating non-existent title" in runTest {
    (scheduledVideoDownload, transaction) =>
      for {
        updateResult <- transaction {
          DoobieVideoTitleDao.update(scheduledVideoDownload.videoMetadata.id, "non-existent", "New Title")
        }

        _ <- IO.delay {
          updateResult mustBe 0
        }
      } yield (): Unit
  }

  it should "delete video title by videoId" in runTest {
    (scheduledVideoDownload, transaction) =>
      for {
        _ <- transaction { insertTestUser("user-del-1", "userdel1@test.com") }

        videoTitle = VideoTitle(scheduledVideoDownload.videoMetadata.id, "user-del-1", "Title to Delete")
        _ <- transaction {
          DoobieVideoTitleDao.insert(videoTitle)
        }

        deleteResult <- transaction {
          DoobieVideoTitleDao.delete(Some(scheduledVideoDownload.videoMetadata.id), None)
        }

        _ <- IO.delay {
          deleteResult mustBe 1
        }

        foundTitle <- transaction {
          DoobieVideoTitleDao.find(scheduledVideoDownload.videoMetadata.id, "user-del-1")
        }

        _ <- IO.delay {
          foundTitle mustBe None
        }
      } yield (): Unit
  }

  it should "delete video title by userId" in runTest {
    (scheduledVideoDownload, transaction) =>
      for {
        _ <- transaction { insertTestUser("user-del-2", "userdel2@test.com") }

        videoTitle = VideoTitle(scheduledVideoDownload.videoMetadata.id, "user-del-2", "Title to Delete")
        _ <- transaction {
          DoobieVideoTitleDao.insert(videoTitle)
        }

        deleteResult <- transaction {
          DoobieVideoTitleDao.delete(None, Some("user-del-2"))
        }

        _ <- IO.delay {
          deleteResult mustBe 1
        }
      } yield (): Unit
  }

  it should "delete video title by both videoId and userId" in runTest {
    (scheduledVideoDownload, transaction) =>
      for {
        _ <- transaction { insertTestUser("user-del-3", "userdel3@test.com") }
        _ <- transaction { insertTestUser("user-del-4", "userdel4@test.com") }

        videoTitle1 = VideoTitle(scheduledVideoDownload.videoMetadata.id, "user-del-3", "Title 1")
        videoTitle2 = VideoTitle(scheduledVideoDownload.videoMetadata.id, "user-del-4", "Title 2")
        _ <- transaction {
          DoobieVideoTitleDao.insert(videoTitle1)
        }
        _ <- transaction {
          DoobieVideoTitleDao.insert(videoTitle2)
        }

        deleteResult <- transaction {
          DoobieVideoTitleDao.delete(Some(scheduledVideoDownload.videoMetadata.id), Some("user-del-3"))
        }

        _ <- IO.delay {
          deleteResult mustBe 1
        }

        foundTitle1 <- transaction {
          DoobieVideoTitleDao.find(scheduledVideoDownload.videoMetadata.id, "user-del-3")
        }
        foundTitle2 <- transaction {
          DoobieVideoTitleDao.find(scheduledVideoDownload.videoMetadata.id, "user-del-4")
        }

        _ <- IO.delay {
          foundTitle1 mustBe None
          foundTitle2 mustBe defined
        }
      } yield (): Unit
  }

  it should "fail when both videoId and userId are empty for delete" in runTest {
    (_, transaction) =>
      for {
        result <- transaction {
          DoobieVideoTitleDao.delete(None, None)
        }.attempt

        _ <- IO.delay {
          result.isLeft mustBe true
          result.left.exists(_.isInstanceOf[IllegalArgumentException]) mustBe true
          result.left.exists(_.getMessage.contains("Both videoId and userId cannot be empty")) mustBe true
        }
      } yield (): Unit
  }

  it should "return 0 when deleting non-existent title" in runTest {
    (_, transaction) =>
      for {
        deleteResult <- transaction {
          DoobieVideoTitleDao.delete(Some("non-existent-video"), None)
        }

        _ <- IO.delay {
          deleteResult mustBe 0
        }
      } yield (): Unit
  }

  it should "handle titles with special characters" in runTest {
    (scheduledVideoDownload, transaction) =>
      for {
        _ <- transaction { insertTestUser("user-special", "userspecial@test.com") }

        specialTitle = "Title with 'quotes' and \"double quotes\" & ampersand"
        videoTitle = VideoTitle(scheduledVideoDownload.videoMetadata.id, "user-special", specialTitle)
        _ <- transaction {
          DoobieVideoTitleDao.insert(videoTitle)
        }

        foundTitle <- transaction {
          DoobieVideoTitleDao.find(scheduledVideoDownload.videoMetadata.id, "user-special")
        }

        _ <- IO.delay {
          foundTitle mustBe defined
          foundTitle.value.title mustBe specialTitle
        }
      } yield (): Unit
  }

  it should "handle empty title" in runTest {
    (scheduledVideoDownload, transaction) =>
      for {
        _ <- transaction { insertTestUser("user-empty", "userempty@test.com") }

        videoTitle = VideoTitle(scheduledVideoDownload.videoMetadata.id, "user-empty", "")
        _ <- transaction {
          DoobieVideoTitleDao.insert(videoTitle)
        }

        foundTitle <- transaction {
          DoobieVideoTitleDao.find(scheduledVideoDownload.videoMetadata.id, "user-empty")
        }

        _ <- IO.delay {
          foundTitle mustBe defined
          foundTitle.value.title mustBe ""
        }
      } yield (): Unit
  }

  it should "support multiple users having titles for the same video" in runTest {
    (scheduledVideoDownload, transaction) =>
      for {
        _ <- transaction { insertTestUser("user-multi-1", "usermulti1@test.com") }
        _ <- transaction { insertTestUser("user-multi-2", "usermulti2@test.com") }

        videoTitle1 = VideoTitle(scheduledVideoDownload.videoMetadata.id, "user-multi-1", "User 1 Title")
        videoTitle2 = VideoTitle(scheduledVideoDownload.videoMetadata.id, "user-multi-2", "User 2 Title")
        _ <- transaction {
          DoobieVideoTitleDao.insert(videoTitle1)
        }
        _ <- transaction {
          DoobieVideoTitleDao.insert(videoTitle2)
        }

        foundTitle1 <- transaction {
          DoobieVideoTitleDao.find(scheduledVideoDownload.videoMetadata.id, "user-multi-1")
        }
        foundTitle2 <- transaction {
          DoobieVideoTitleDao.find(scheduledVideoDownload.videoMetadata.id, "user-multi-2")
        }

        _ <- IO.delay {
          foundTitle1.value.title mustBe "User 1 Title"
          foundTitle2.value.title mustBe "User 2 Title"
        }
      } yield (): Unit
  }
}
