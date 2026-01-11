package com.ruchij.core.daos.videowatchhistory

import cats.effect.IO
import cats.~>
import com.ruchij.core.daos.resource.DoobieFileResourceDao
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.video.DoobieVideoDao
import com.ruchij.core.daos.videometadata.DoobieVideoMetadataDao
import com.ruchij.core.daos.videometadata.models.{CustomVideoSite, VideoMetadata}
import com.ruchij.core.daos.videowatchhistory.models.VideoWatchHistory
import com.ruchij.core.external.embedded.EmbeddedCoreResourcesProvider
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.types.JodaClock
import doobie.ConnectionIO
import doobie.implicits._
import org.http4s.MediaType
import org.http4s.implicits.http4sLiteralsSyntax
import org.joda.time.DateTime
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps

class DoobieVideoWatchHistoryDaoSpec extends AnyFlatSpec with Matchers with OptionValues {

  import com.ruchij.core.daos.doobie.DoobieCustomMappings.{dateTimeGet, dateTimePut}

  case class TestFixture(
    videoWatchHistory: VideoWatchHistory,
    videoId: String,
    userId: String,
    transaction: ConnectionIO ~> IO
  )

  private def insertTestUser(userId: String, timestamp: DateTime): ConnectionIO[Int] =
    sql"""
      INSERT INTO api_user (id, created_at, first_name, last_name, email, role)
        VALUES ($userId, $timestamp, 'Test', 'User', 'test@example.com', 'User')
    """.update.run

  def runTest(testFn: TestFixture => IO[Unit]): Unit =
    runIO {
      new EmbeddedCoreResourcesProvider[IO].transactor.use { transaction =>
        for {
          timestamp <- JodaClock[IO].timestamp
          userId = "test-user-id"
          videoId = "test-video-id"

          _ <- transaction(insertTestUser(userId, timestamp))

          thumbnailFileResource = FileResource(
            "thumbnail-id",
            timestamp,
            "/opt/image/thumbnail.jpg",
            MediaType.image.jpeg,
            100
          )
          _ <- transaction(DoobieFileResourceDao.insert(thumbnailFileResource))

          videoMetadata = VideoMetadata(
            uri"https://example.com/video",
            videoId,
            CustomVideoSite.SpankBang,
            "Test Video Title",
            5 minutes,
            50000,
            thumbnailFileResource
          )
          _ <- transaction(DoobieVideoMetadataDao.insert(videoMetadata))

          videoFileResource = FileResource(
            "video-file-id",
            timestamp,
            "/opt/video/test-video.mp4",
            MediaType.video.mp4,
            50000
          )
          _ <- transaction(DoobieFileResourceDao.insert(videoFileResource))

          _ <- transaction(DoobieVideoDao.insert(videoId, videoFileResource.id, timestamp, 0 seconds))

          videoWatchHistory = VideoWatchHistory(
            "watch-history-id",
            userId,
            videoId,
            timestamp,
            timestamp,
            30 seconds
          )

          result <- testFn(TestFixture(videoWatchHistory, videoId, userId, transaction))
        } yield result
      }
    }

  "DoobieVideoWatchHistoryDao" should "insert and retrieve video watch history" in runTest { fixture =>
    for {
      _ <- fixture.transaction(DoobieVideoWatchHistoryDao.insert(fixture.videoWatchHistory))

      results <- fixture.transaction(
        DoobieVideoWatchHistoryDao.findBy(fixture.userId, 10, 0)
      )

      _ <- IO.delay {
        results.size mustBe 1
        results.head.id mustBe fixture.videoWatchHistory.id
        results.head.userId mustBe fixture.videoWatchHistory.userId
        results.head.video.videoMetadata.id mustBe fixture.videoId
        results.head.duration mustBe fixture.videoWatchHistory.duration
      }
    } yield ()
  }

  it should "find video watch history by user ID and video ID" in runTest { fixture =>
    for {
      _ <- fixture.transaction(DoobieVideoWatchHistoryDao.insert(fixture.videoWatchHistory))

      results <- fixture.transaction(
        DoobieVideoWatchHistoryDao.findBy(fixture.userId, fixture.videoId, 10, 0)
      )

      _ <- IO.delay {
        results.size mustBe 1
        results.head.id mustBe fixture.videoWatchHistory.id
        results.head.video.videoMetadata.id mustBe fixture.videoId
      }
    } yield ()
  }

  it should "find video watch history updated after a timestamp" in runTest { fixture =>
    for {
      _ <- fixture.transaction(DoobieVideoWatchHistoryDao.insert(fixture.videoWatchHistory))
      timestamp <- JodaClock[IO].timestamp

      pastTimestamp = timestamp.minusHours(1)
      futureTimestamp = timestamp.plusHours(1)

      resultPast <- fixture.transaction(
        DoobieVideoWatchHistoryDao.findLastUpdatedAfter(fixture.userId, fixture.videoId, pastTimestamp)
      )

      resultFuture <- fixture.transaction(
        DoobieVideoWatchHistoryDao.findLastUpdatedAfter(fixture.userId, fixture.videoId, futureTimestamp)
      )

      _ <- IO.delay {
        resultPast mustBe defined
        resultPast.value.id mustBe fixture.videoWatchHistory.id
        resultFuture mustBe None
      }
    } yield ()
  }

  it should "update video watch history" in runTest { fixture =>
    val updatedDuration = 2 minutes

    for {
      _ <- fixture.transaction(DoobieVideoWatchHistoryDao.insert(fixture.videoWatchHistory))

      timestamp <- JodaClock[IO].timestamp
      updatedWatchHistory = fixture.videoWatchHistory.copy(
        lastUpdatedAt = timestamp,
        duration = updatedDuration
      )

      _ <- fixture.transaction(DoobieVideoWatchHistoryDao.update(updatedWatchHistory))

      results <- fixture.transaction(
        DoobieVideoWatchHistoryDao.findBy(fixture.userId, 10, 0)
      )

      _ <- IO.delay {
        results.size mustBe 1
        results.head.duration mustBe updatedDuration
        results.head.lastUpdatedAt.getMillis mustBe timestamp.getMillis
      }
    } yield ()
  }

  it should "delete video watch history by video ID" in runTest { fixture =>
    for {
      _ <- fixture.transaction(DoobieVideoWatchHistoryDao.insert(fixture.videoWatchHistory))

      resultsBefore <- fixture.transaction(
        DoobieVideoWatchHistoryDao.findBy(fixture.userId, 10, 0)
      )
      _ <- IO.delay { resultsBefore.size mustBe 1 }

      deleteCount <- fixture.transaction(
        DoobieVideoWatchHistoryDao.deleteBy(fixture.videoId)
      )

      resultsAfter <- fixture.transaction(
        DoobieVideoWatchHistoryDao.findBy(fixture.userId, 10, 0)
      )

      _ <- IO.delay {
        deleteCount mustBe 1
        resultsAfter mustBe empty
      }
    } yield ()
  }

  it should "return empty list when no watch history exists for user" in runTest { fixture =>
    for {
      results <- fixture.transaction(
        DoobieVideoWatchHistoryDao.findBy("non-existent-user", 10, 0)
      )

      _ <- IO.delay {
        results mustBe empty
      }
    } yield ()
  }

  it should "return 0 when deleting non-existent video watch history" in runTest { fixture =>
    for {
      deleteCount <- fixture.transaction(
        DoobieVideoWatchHistoryDao.deleteBy("non-existent-video-id")
      )

      _ <- IO.delay {
        deleteCount mustBe 0
      }
    } yield ()
  }

  it should "support pagination when retrieving watch history" in runTest { fixture =>
    for {
      timestamp <- JodaClock[IO].timestamp

      _ <- fixture.transaction(DoobieVideoWatchHistoryDao.insert(fixture.videoWatchHistory))

      thumbnailFileResource2 = FileResource(
        "thumbnail-id-2",
        timestamp,
        "/opt/image/thumbnail2.jpg",
        MediaType.image.jpeg,
        100
      )
      _ <- fixture.transaction(DoobieFileResourceDao.insert(thumbnailFileResource2))

      videoMetadata2 = VideoMetadata(
        uri"https://example.com/video2",
        "test-video-id-2",
        CustomVideoSite.SpankBang,
        "Test Video Title 2",
        10 minutes,
        100000,
        thumbnailFileResource2
      )
      _ <- fixture.transaction(DoobieVideoMetadataDao.insert(videoMetadata2))

      videoFileResource2 = FileResource(
        "video-file-id-2",
        timestamp,
        "/opt/video/test-video2.mp4",
        MediaType.video.mp4,
        100000
      )
      _ <- fixture.transaction(DoobieFileResourceDao.insert(videoFileResource2))
      _ <- fixture.transaction(DoobieVideoDao.insert("test-video-id-2", videoFileResource2.id, timestamp, 0 seconds))

      watchHistory2 = VideoWatchHistory(
        "watch-history-id-2",
        fixture.userId,
        "test-video-id-2",
        timestamp.plusMinutes(1),
        timestamp.plusMinutes(1),
        45 seconds
      )
      _ <- fixture.transaction(DoobieVideoWatchHistoryDao.insert(watchHistory2))

      page1 <- fixture.transaction(
        DoobieVideoWatchHistoryDao.findBy(fixture.userId, 1, 0)
      )
      page2 <- fixture.transaction(
        DoobieVideoWatchHistoryDao.findBy(fixture.userId, 1, 1)
      )
      allResults <- fixture.transaction(
        DoobieVideoWatchHistoryDao.findBy(fixture.userId, 10, 0)
      )

      _ <- IO.delay {
        page1.size mustBe 1
        page2.size mustBe 1
        allResults.size mustBe 2
        page1.head.id must not be page2.head.id
      }
    } yield ()
  }

  it should "return watch history ordered by last updated at descending" in runTest { fixture =>
    for {
      timestamp <- JodaClock[IO].timestamp

      _ <- fixture.transaction(DoobieVideoWatchHistoryDao.insert(fixture.videoWatchHistory))

      thumbnailFileResource2 = FileResource(
        "thumbnail-id-2",
        timestamp,
        "/opt/image/thumbnail2.jpg",
        MediaType.image.jpeg,
        100
      )
      _ <- fixture.transaction(DoobieFileResourceDao.insert(thumbnailFileResource2))

      videoMetadata2 = VideoMetadata(
        uri"https://example.com/video2",
        "test-video-id-2",
        CustomVideoSite.SpankBang,
        "Test Video Title 2",
        10 minutes,
        100000,
        thumbnailFileResource2
      )
      _ <- fixture.transaction(DoobieVideoMetadataDao.insert(videoMetadata2))

      videoFileResource2 = FileResource(
        "video-file-id-2",
        timestamp,
        "/opt/video/test-video2.mp4",
        MediaType.video.mp4,
        100000
      )
      _ <- fixture.transaction(DoobieFileResourceDao.insert(videoFileResource2))
      _ <- fixture.transaction(DoobieVideoDao.insert("test-video-id-2", videoFileResource2.id, timestamp, 0 seconds))

      laterTimestamp = timestamp.plusHours(1)
      watchHistory2 = VideoWatchHistory(
        "watch-history-id-2",
        fixture.userId,
        "test-video-id-2",
        timestamp,
        laterTimestamp,
        45 seconds
      )
      _ <- fixture.transaction(DoobieVideoWatchHistoryDao.insert(watchHistory2))

      results <- fixture.transaction(
        DoobieVideoWatchHistoryDao.findBy(fixture.userId, 10, 0)
      )

      _ <- IO.delay {
        results.size mustBe 2
        results.head.id mustBe "watch-history-id-2"
        results.head.lastUpdatedAt.getMillis mustBe laterTimestamp.getMillis
      }
    } yield ()
  }
}
