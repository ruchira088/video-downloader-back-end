package com.ruchij.core.daos.schedulling

import cats.data.NonEmptyList
import cats.effect.IO
import cats.implicits._
import cats.~>
import com.ruchij.core.daos.resource.DoobieFileResourceDao
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.scheduling.DoobieSchedulingDao
import com.ruchij.core.daos.scheduling.models.{ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.daos.videometadata.DoobieVideoMetadataDao
import com.ruchij.core.daos.videometadata.models.{VideoMetadata, VideoSite}
import com.ruchij.core.services.models.{Order, SortBy}
import com.ruchij.core.services.video.models.DurationRange
import com.ruchij.core.test.DoobieProvider
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.test.Providers.contextShift
import com.ruchij.core.types.JodaClock
import doobie.ConnectionIO
import org.http4s.MediaType
import org.http4s.implicits.http4sLiteralsSyntax
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps

class DoobieSchedulingDaoSpec extends AnyFlatSpec with Matchers with OptionValues {

  def runTest(testFn: (ScheduledVideoDownload, ConnectionIO ~> IO) => IO[Unit]): Unit =
    runIO {
      DoobieProvider.inMemoryTransactor[IO].use {
        transaction =>
          for {
            timestamp <- JodaClock.create[IO].timestamp
            thumbnailFileResource = FileResource("thumbnail-id", timestamp, "/opt/image/thumbnail.jpg", MediaType.image.jpeg, 100)
            _ <- transaction {
              DoobieFileResourceDao.insert(thumbnailFileResource)
            }

            videoMetadata =
              VideoMetadata(
                uri"https://spankbang.com",
                "video-metadata-id",
                VideoSite.SpankBang,
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
                None
              )
            _ <- transaction {
              DoobieSchedulingDao.insert(scheduledVideoDownload)
            }

            maybeScheduledVideoDownload <-
              transaction { DoobieSchedulingDao.getById(scheduledVideoDownload.videoMetadata.id) }

            _ = {
              maybeScheduledVideoDownload.value.videoMetadata.id mustBe videoMetadata.id
              maybeScheduledVideoDownload.value.videoMetadata.size mustBe videoMetadata.size
              maybeScheduledVideoDownload.value.videoMetadata.duration mustBe videoMetadata.duration
              maybeScheduledVideoDownload.value.videoMetadata.videoSite mustBe videoMetadata.videoSite
              maybeScheduledVideoDownload.value.videoMetadata.url mustBe videoMetadata.url
              maybeScheduledVideoDownload.value.videoMetadata.thumbnail.id mustBe thumbnailFileResource.id
              maybeScheduledVideoDownload.value.videoMetadata.thumbnail.size mustBe thumbnailFileResource.size
              maybeScheduledVideoDownload.value.videoMetadata.thumbnail.path mustBe thumbnailFileResource.path
              maybeScheduledVideoDownload.value.videoMetadata.thumbnail.mediaType mustBe thumbnailFileResource.mediaType
              maybeScheduledVideoDownload.value.videoMetadata.thumbnail.createdAt.getMillis mustBe thumbnailFileResource.createdAt.getMillis
              maybeScheduledVideoDownload.value.scheduledAt.getMillis mustBe scheduledVideoDownload.scheduledAt.getMillis
              maybeScheduledVideoDownload.value.status mustBe scheduledVideoDownload.status
              maybeScheduledVideoDownload.value.downloadedBytes mustBe scheduledVideoDownload.downloadedBytes
              maybeScheduledVideoDownload.value.lastUpdatedAt.getMillis mustBe scheduledVideoDownload.lastUpdatedAt.getMillis
              maybeScheduledVideoDownload.value.completedAt.map(_.getMillis) mustBe scheduledVideoDownload.completedAt.map(_.getMillis)
            }

            result <- testFn(maybeScheduledVideoDownload.value, transaction)
          }
          yield result
      }
    }

  "DoobieSchedulingDao" should "perform correctly perform search queries" in runTest {  (scheduledVideoDownload, transaction) =>
    for {
      searchResultOne <-
        transaction {
          DoobieSchedulingDao.search(None, None, DurationRange.All, 0, 10, SortBy.Date, Order.Descending, None)
       }
      _ = { searchResultOne mustBe Seq(scheduledVideoDownload) }

      searchResultTwo <-
        transaction {
          DoobieSchedulingDao.search(Some("sample"), None, DurationRange.All, 0, 10, SortBy.Date, Order.Descending, None)
        }
      _ = { searchResultTwo mustBe Seq(scheduledVideoDownload) }

      searchResultThree <-
        transaction {
          DoobieSchedulingDao.search(Some("non-existent"), None, DurationRange.All, 0, 10, SortBy.Date, Order.Descending, None)
        }
      _ = { searchResultThree mustBe Seq.empty }

      searchResultFour <-
        transaction {
          DoobieSchedulingDao.search(None, None, DurationRange(None, Some(6 minutes)), 0, 10, SortBy.Date, Order.Descending, None)
        }
      _ = { searchResultFour mustBe Seq(scheduledVideoDownload) }

      searchResultFive <-
        transaction {
          DoobieSchedulingDao.search(None, None, DurationRange(None, Some(4 minutes)), 0, 10, SortBy.Date, Order.Descending, None)
        }
      _ = { searchResultFive mustBe Seq.empty }

      searchResultSix <-
        transaction {
          DoobieSchedulingDao.search(None, None, DurationRange(Some(4 minutes), None), 0, 10, SortBy.Date, Order.Descending, None)
        }
      _ = { searchResultSix mustBe Seq(scheduledVideoDownload) }

      searchResultSeven <-
        transaction {
          DoobieSchedulingDao.search(None, None, DurationRange(Some(6 minutes), None), 0, 10, SortBy.Date, Order.Descending, None)
        }
      _ = { searchResultSeven mustBe Seq.empty }

      searchResultEight <-
        transaction {
          DoobieSchedulingDao.search(None, None, DurationRange.All, 0, 10, SortBy.Date, Order.Descending, Some(NonEmptyList.one(SchedulingStatus.Queued)))
        }
      _ = { searchResultEight mustBe Seq(scheduledVideoDownload) }

      searchResultNine <-
        transaction {
          DoobieSchedulingDao.search(None, None, DurationRange.All, 0, 10, SortBy.Date, Order.Descending, Some(NonEmptyList.one(SchedulingStatus.Completed)))
        }
      _ = { searchResultNine mustBe Seq.empty }

    }
    yield (): Unit
  }

  it should "complete the scheduled download video task" in runTest {
    (scheduledVideoDownload, transaction) =>
      for {
        timestamp <- JodaClock.create[IO].timestamp
        maybeUpdated <-
          transaction {
            DoobieSchedulingDao.completeTask(scheduledVideoDownload.videoMetadata.id, timestamp)
          }

        _ = {
          maybeUpdated.value.completedAt.map(_.getMillis) mustBe Some(timestamp.getMillis)
          maybeUpdated.value.status mustBe SchedulingStatus.Completed
          maybeUpdated.value.lastUpdatedAt.getMillis mustBe timestamp.getMillis

          maybeUpdated.value.copy(completedAt = scheduledVideoDownload.completedAt, status = scheduledVideoDownload.status, lastUpdatedAt = scheduledVideoDownload.lastUpdatedAt) mustBe scheduledVideoDownload
        }
      }
      yield (): Unit
  }

  it should "update the status of the scheduled video download" in runTest {
    (scheduledVideoDownload, transaction) =>
      for {
        timestamp <- JodaClock.create[IO].timestamp
        maybeUpdated <-
          transaction {
            DoobieSchedulingDao.updateStatus(scheduledVideoDownload.videoMetadata.id, SchedulingStatus.Active, timestamp)
          }

        _ = {
          maybeUpdated.value.status mustBe SchedulingStatus.Active
          maybeUpdated.value.lastUpdatedAt.getMillis mustBe timestamp.getMillis

          maybeUpdated.value.copy(status = scheduledVideoDownload.status, lastUpdatedAt = scheduledVideoDownload.lastUpdatedAt) mustBe scheduledVideoDownload
        }
      }
      yield (): Unit
  }

  it should "update the download progress" in runTest {
    (scheduledVideoDownload, transaction) =>
      for {
        timestamp <- JodaClock.create[IO].timestamp
        maybeUpdated <-
          transaction {
            DoobieSchedulingDao.updateDownloadProgress(scheduledVideoDownload.videoMetadata.id, 2021, timestamp)
          }

        _ = {
          maybeUpdated.value.downloadedBytes mustBe 2021
          maybeUpdated.value.lastUpdatedAt.getMillis mustBe timestamp.getMillis

          maybeUpdated.value.copy(downloadedBytes = scheduledVideoDownload.downloadedBytes, lastUpdatedAt = scheduledVideoDownload.lastUpdatedAt) mustBe scheduledVideoDownload
        }
      }
      yield (): Unit
  }

  it should "update timed-out tasks and return a stale task" in runTest {
    (scheduledVideoDownload, transaction) =>
      for {
        timestampOne <- JodaClock.create[IO].timestamp
        timestampTwo = timestampOne.plusSeconds(20)

        timedOutTasks <-
          transaction {
            DoobieSchedulingDao.updateStatus(scheduledVideoDownload.videoMetadata.id, SchedulingStatus.Active, timestampOne)
              .productR {
                DoobieSchedulingDao.updateTimedOutTasks(10 seconds, timestampTwo)
              }
          }

        _ = {
          timedOutTasks.size mustBe 1
          timedOutTasks.headOption.value.status mustBe SchedulingStatus.Stale
          timedOutTasks.headOption.value.lastUpdatedAt.getMillis mustBe timestampTwo.getMillis

          timedOutTasks.map(_.copy(lastUpdatedAt = scheduledVideoDownload.lastUpdatedAt, status = scheduledVideoDownload.status)) mustBe Seq(scheduledVideoDownload)
        }

        maybeStaleTask <- transaction { DoobieSchedulingDao.staleTask(timestampTwo) }

        _ = {
          maybeStaleTask.value.status mustBe SchedulingStatus.Acquired
          maybeStaleTask.value.lastUpdatedAt.getMillis mustBe timestampTwo.getMillis

          maybeStaleTask.value.copy(lastUpdatedAt = scheduledVideoDownload.lastUpdatedAt, status = scheduledVideoDownload.status) mustBe scheduledVideoDownload
        }

        moreTimedOutTasks <-
          transaction(DoobieSchedulingDao.updateTimedOutTasks(10 seconds, timestampTwo))

        _ = { moreTimedOutTasks mustBe Seq.empty }

        maybeMoreStaledTasks <- transaction { DoobieSchedulingDao.staleTask(timestampTwo) }

        _ = maybeMoreStaledTasks mustBe None
      }
      yield (): Unit
  }

  it should "acquire queued scheduled video downloads" in runTest {
    (scheduledVideDownload, transaction) =>
      for {
        timestamp <- JodaClock.create[IO].timestamp

        _ <- transaction {
          DoobieSchedulingDao.updateStatus(scheduledVideDownload.videoMetadata.id, SchedulingStatus.Queued, timestamp)
        }

        maybeAcquiredTask <- transaction(DoobieSchedulingDao.acquireTask(timestamp))

        _ = {
          maybeAcquiredTask.value.status mustBe SchedulingStatus.Acquired
          maybeAcquiredTask.value.lastUpdatedAt.getMillis mustBe timestamp.getMillis

          maybeAcquiredTask.value.copy(status = scheduledVideDownload.status, lastUpdatedAt = scheduledVideDownload.lastUpdatedAt) mustBe scheduledVideDownload
        }

        maybeAnotherTask <- transaction(DoobieSchedulingDao.acquireTask(timestamp))

        _ = { maybeAnotherTask mustBe None }
      }
      yield (): Unit
  }

  it should "delete the scheduled video download" in runTest {
    (scheduledVideoDownload, transaction) =>
      for {
        maybeDeleted <- transaction(DoobieSchedulingDao.deleteById(scheduledVideoDownload.videoMetadata.id))

        _ = { maybeDeleted.value mustBe scheduledVideoDownload }

        maybeDeleteAgain <- transaction(DoobieSchedulingDao.deleteById(scheduledVideoDownload.videoMetadata.id))

        _ = { maybeDeleteAgain mustBe None }
      }
      yield (): Unit
  }

}
