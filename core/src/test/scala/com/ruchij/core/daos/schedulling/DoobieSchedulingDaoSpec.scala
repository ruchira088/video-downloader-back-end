package com.ruchij.core.daos.schedulling

import cats.data.NonEmptyList
import cats.effect.IO
import cats.implicits._
import cats.~>
import com.ruchij.core.daos.resource.DoobieFileResourceDao
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.scheduling.DoobieSchedulingDao
import com.ruchij.core.daos.scheduling.models.{RangeValue, ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.daos.videometadata.DoobieVideoMetadataDao
import com.ruchij.core.daos.videometadata.models.{CustomVideoSite, VideoMetadata}
import com.ruchij.core.services.models.{Order, SortBy}
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.external.embedded.EmbeddedCoreResourcesProvider
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
      new EmbeddedCoreResourcesProvider[IO].transactor.use {
        transaction =>
          for {
            timestamp <- JodaClock[IO].timestamp
            thumbnailFileResource = FileResource("thumbnail-id", timestamp, "/opt/image/thumbnail.jpg", MediaType.image.jpeg, 100)
            _ <- transaction {
              DoobieFileResourceDao.insert(thumbnailFileResource)
            }

            videoMetadata =
              VideoMetadata(
                uri"https://spankbang.com",
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

            maybeScheduledVideoDownload <-
              transaction { DoobieSchedulingDao.getById(scheduledVideoDownload.videoMetadata.id, None) }

            _ <- IO.delay {
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
              maybeScheduledVideoDownload.value.errorInfo mustBe None
            }

            result <- testFn(maybeScheduledVideoDownload.value, transaction)
          }
          yield result
      }
    }

  "DoobieSchedulingDao" should "correctly perform search queries" in runTest {  (scheduledVideoDownload, transaction) =>
    for {
      searchResultOne <-
        transaction {
          DoobieSchedulingDao.search(None, None, RangeValue.all[FiniteDuration], RangeValue.all[Long], 0, 10, SortBy.Date, Order.Descending, None, None, None)
       }
      _ <- IO.delay {  searchResultOne mustBe Seq(scheduledVideoDownload) }

      searchResultTwo <-
        transaction {
          DoobieSchedulingDao.search(Some("sample"), None, RangeValue.all[FiniteDuration], RangeValue.all[Long], 0, 10, SortBy.Date, Order.Descending, None, Some(NonEmptyList.one(CustomVideoSite.SpankBang)), None)
        }
      _ <- IO.delay {  searchResultTwo mustBe Seq(scheduledVideoDownload) }

      searchResultThree <-
        transaction {
          DoobieSchedulingDao.search(Some("non-existent"), None, RangeValue.all[FiniteDuration], RangeValue.all[Long], 0, 10, SortBy.Date, Order.Descending, None, None, None)
        }
      _ <- IO.delay {  searchResultThree mustBe Seq.empty }

      searchResultFour <-
        transaction {
          DoobieSchedulingDao.search(None, None, RangeValue[FiniteDuration](None, Some(6 minutes)), RangeValue.all[Long], 0, 10, SortBy.Date, Order.Descending, None, None, None)
        }
      _ <- IO.delay {  searchResultFour mustBe Seq(scheduledVideoDownload) }

      searchResultFive <-
        transaction {
          DoobieSchedulingDao.search(None, None, RangeValue[FiniteDuration](None, Some(4 minutes)), RangeValue.all[Long], 0, 10, SortBy.Date, Order.Descending, None, None, None)
        }
      _ <- IO.delay {  searchResultFive mustBe Seq.empty }

      searchResultSix <-
        transaction {
          DoobieSchedulingDao.search(None, None, RangeValue[FiniteDuration](Some(4 minutes), None), RangeValue.all[Long], 0, 10, SortBy.Date, Order.Descending, None, None, None)
        }
      _ <- IO.delay {  searchResultSix mustBe Seq(scheduledVideoDownload) }

      searchResultSeven <-
        transaction {
          DoobieSchedulingDao.search(None, None, RangeValue[FiniteDuration](Some(6 minutes), None), RangeValue.all[Long], 0, 10, SortBy.Date, Order.Descending, None, None, None)
        }
      _ <- IO.delay {  searchResultSeven mustBe Seq.empty }

      searchResultEight <-
        transaction {
          DoobieSchedulingDao.search(None, None, RangeValue.all[FiniteDuration], RangeValue.all[Long], 0, 10, SortBy.Date, Order.Descending, Some(NonEmptyList.one(SchedulingStatus.Queued)), None, None)
        }
      _ <- IO.delay { searchResultEight mustBe Seq(scheduledVideoDownload) }

      searchResultNine <-
        transaction {
          DoobieSchedulingDao.search(None, None, RangeValue.all[FiniteDuration], RangeValue.all[Long], 0, 10, SortBy.Date, Order.Descending, Some(NonEmptyList.one(SchedulingStatus.Completed)), None, None)
        }
      _ <- IO.delay {  searchResultNine mustBe Seq.empty }

      searchResultTen <-
        transaction {
          DoobieSchedulingDao.search(None, None, RangeValue.all[FiniteDuration], RangeValue.all[Long], 0, 10, SortBy.Date, Order.Descending, None, Some(NonEmptyList.one(CustomVideoSite.PornOne)), None)
        }
      _ <- IO.delay {  searchResultTen mustBe Seq.empty }

      searchResultEleven <-
        transaction {
          DoobieSchedulingDao.search(None, None, RangeValue.all[FiniteDuration], RangeValue(Some(40_000), None), 0, 10, SortBy.Date, Order.Descending, None, None, None)
        }
      _ <- IO.delay {  searchResultEleven mustBe Seq(scheduledVideoDownload) }

      searchResultTwelve <-
        transaction {
          DoobieSchedulingDao.search(None, None, RangeValue.all[FiniteDuration], RangeValue(Some(60_000), None), 0, 10, SortBy.Date, Order.Descending, None, None, None)
        }
      _ <- IO.delay {  searchResultTwelve mustBe Seq.empty }
    }
    yield (): Unit
  }

  it should "complete the scheduled download video task" in runTest {
    (scheduledVideoDownload, transaction) =>
      for {
        timestamp <- JodaClock[IO].timestamp
        maybeUpdated <-
          transaction {
            DoobieSchedulingDao.markScheduledVideoDownloadAsComplete(scheduledVideoDownload.videoMetadata.id, timestamp)
          }

        _ <- IO.delay {
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
        timestamp <- JodaClock[IO].timestamp
        maybeUpdated <-
          transaction {
            DoobieSchedulingDao.updateSchedulingStatusById(scheduledVideoDownload.videoMetadata.id, SchedulingStatus.Active, timestamp)
          }

        _ <- IO.delay {
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
        timestamp <- JodaClock[IO].timestamp
        maybeUpdated <-
          transaction {
            DoobieSchedulingDao.updateDownloadProgress(scheduledVideoDownload.videoMetadata.id, 2021, timestamp)
          }

        _ <- IO.delay {
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
        timestampZero <- JodaClock[IO].timestamp
        timestampTwenty = timestampZero.plusSeconds(20)
        timestampForty = timestampTwenty.plusSeconds(20)

        timedOutTasks <-
          transaction {
            DoobieSchedulingDao.updateSchedulingStatusById(scheduledVideoDownload.videoMetadata.id, SchedulingStatus.Active, timestampZero)
              .productR {
                DoobieSchedulingDao.updateTimedOutTasks(10 seconds, timestampTwenty)
              }
          }

        _ <- IO.delay {
          timedOutTasks.size mustBe 1
          timedOutTasks.headOption.value.status mustBe SchedulingStatus.Stale
          timedOutTasks.headOption.value.lastUpdatedAt.getMillis mustBe timestampTwenty.getMillis

          timedOutTasks.map(_.copy(lastUpdatedAt = scheduledVideoDownload.lastUpdatedAt, status = scheduledVideoDownload.status)) mustBe Seq(scheduledVideoDownload)
        }

        maybeStaleTask <- transaction {
          DoobieSchedulingDao.staleTask(10 seconds, timestampForty)
        }

        _ <- IO.delay {
          maybeStaleTask.value.status mustBe SchedulingStatus.Acquired
          maybeStaleTask.value.lastUpdatedAt.getMillis mustBe timestampForty.getMillis

          maybeStaleTask.value.copy(lastUpdatedAt = scheduledVideoDownload.lastUpdatedAt, status = scheduledVideoDownload.status) mustBe scheduledVideoDownload
        }

        moreTimedOutTasks <-
          transaction(DoobieSchedulingDao.updateTimedOutTasks(10 seconds, timestampForty))

        _ <- IO.delay {  moreTimedOutTasks mustBe Seq.empty }

        maybeMoreStaledTasks <- transaction {
          DoobieSchedulingDao.staleTask(10 seconds, timestampForty)
        }

        _ <- IO.delay { maybeMoreStaledTasks mustBe None }
      }
      yield (): Unit
  }

  it should "acquire queued scheduled video downloads" in runTest {
    (scheduledVideDownload, transaction) =>
      for {
        timestamp <- JodaClock[IO].timestamp

        _ <- transaction {
          DoobieSchedulingDao.updateSchedulingStatusById(scheduledVideDownload.videoMetadata.id, SchedulingStatus.Queued, timestamp)
        }

        maybeAcquiredTask <- transaction(DoobieSchedulingDao.acquireTask(timestamp))

        _ <- IO.delay {
          maybeAcquiredTask.value.status mustBe SchedulingStatus.Acquired
          maybeAcquiredTask.value.lastUpdatedAt.getMillis mustBe timestamp.getMillis

          maybeAcquiredTask.value.copy(status = scheduledVideDownload.status, lastUpdatedAt = scheduledVideDownload.lastUpdatedAt) mustBe scheduledVideDownload
        }

        maybeAnotherTask <- transaction(DoobieSchedulingDao.acquireTask(timestamp))

        _ <- IO.delay {  maybeAnotherTask mustBe None }
      }
      yield (): Unit
  }

  it should "delete the scheduled video download" in runTest {
    (scheduledVideoDownload, transaction) =>
      for {
        deletionResult <- transaction(DoobieSchedulingDao.deleteById(scheduledVideoDownload.videoMetadata.id))

        _ <- IO.delay {  deletionResult mustBe 1 }

        repeatDeletionResult <- transaction(DoobieSchedulingDao.deleteById(scheduledVideoDownload.videoMetadata.id))

        _ <- IO.delay {  repeatDeletionResult mustBe 0 }
      }
      yield (): Unit
  }

  it should "set error information" in runTest {
    (scheduledVideoDownload, transaction) =>
      for {
        timestamp <- JodaClock[IO].timestamp
        exception = new Exception("This is a test error")
        _ <- transaction {
          DoobieSchedulingDao.setErrorById(scheduledVideoDownload.videoMetadata.id, exception, timestamp)
        }

        maybeUpdated <- transaction(DoobieSchedulingDao.getById(scheduledVideoDownload.videoMetadata.id, None))

        updated <- IO.delay(maybeUpdated.value)

        _ <- IO.delay {
          updated.errorInfo must not be empty
          updated.errorInfo.value.message mustBe exception.getMessage
          updated.errorInfo.value.details mustBe exception.getStackTrace.map(_.toString).mkString("\n")
        }
      }
      yield (): Unit
  }

  it should "reject insert with error info" in runTest {
    (scheduledVideoDownload, transaction) =>
      val errorInfo = ScheduledVideoDownload.ErrorInfo(
        "error-message",
        "error-details"
      )
      val downloadWithError = scheduledVideoDownload.copy(errorInfo = Some(errorInfo))

      for {
        result <- transaction(DoobieSchedulingDao.insert(downloadWithError)).attempt

        _ <- IO.delay {
          result.isLeft mustBe true
          result.left.exists(_.isInstanceOf[IllegalArgumentException]) mustBe true
        }
      }
      yield (): Unit
  }

  it should "update scheduling status in batch" in runTest {
    (scheduledVideoDownload, transaction) =>
      for {
        timestamp <- JodaClock[IO].timestamp

        // Update status to Error first
        _ <- transaction {
          DoobieSchedulingDao.updateSchedulingStatusById(scheduledVideoDownload.videoMetadata.id, SchedulingStatus.Error, timestamp)
        }

        // Batch update from Error to Queued
        updated <- transaction {
          DoobieSchedulingDao.updateSchedulingStatus(SchedulingStatus.Error, SchedulingStatus.Queued)
        }

        _ <- IO.delay {
          updated.size mustBe 1
          updated.head.status mustBe SchedulingStatus.Queued
        }

        // Batch update with no matching status should return empty
        emptyResult <- transaction {
          DoobieSchedulingDao.updateSchedulingStatus(SchedulingStatus.Error, SchedulingStatus.Queued)
        }

        _ <- IO.delay {
          emptyResult mustBe empty
        }
      }
      yield (): Unit
  }

  it should "retry errored scheduled downloads" in runTest {
    (scheduledVideoDownload, transaction) =>
      for {
        timestamp <- JodaClock[IO].timestamp

        // Set error on the scheduled download
        exception = new Exception("Download failed")
        _ <- transaction {
          DoobieSchedulingDao.setErrorById(scheduledVideoDownload.videoMetadata.id, exception, timestamp)
        }

        // Verify it's in Error state
        maybeErrored <- transaction(DoobieSchedulingDao.getById(scheduledVideoDownload.videoMetadata.id, None))
        _ <- IO.delay {
          maybeErrored.value.status mustBe SchedulingStatus.Error
        }

        // Retry errored downloads
        retried <- transaction {
          DoobieSchedulingDao.retryErroredScheduledDownloads(None, timestamp)
        }

        _ <- IO.delay {
          retried.size mustBe 1
          retried.head.status mustBe SchedulingStatus.Queued
          // Note: returned copy has old errorInfo, but database record is cleared
        }

        // Verify database state shows no error
        maybeRetried <- transaction(DoobieSchedulingDao.getById(scheduledVideoDownload.videoMetadata.id, None))
        _ <- IO.delay {
          maybeRetried.value.status mustBe SchedulingStatus.Queued
          maybeRetried.value.errorInfo mustBe None
        }

        // Retry again should return empty
        retriedAgain <- transaction {
          DoobieSchedulingDao.retryErroredScheduledDownloads(None, timestamp)
        }

        _ <- IO.delay {
          retriedAgain mustBe empty
        }
      }
      yield (): Unit
  }

  it should "search with ascending order" in runTest {
    (scheduledVideoDownload, transaction) =>
      for {
        searchResult <- transaction {
          DoobieSchedulingDao.search(None, None, RangeValue.all[FiniteDuration], RangeValue.all[Long], 0, 10, SortBy.Date, Order.Ascending, None, None, None)
        }

        _ <- IO.delay {
          searchResult mustBe Seq(scheduledVideoDownload)
        }
      }
      yield (): Unit
  }

  it should "search with video URLs filter" in runTest {
    (scheduledVideoDownload, transaction) =>
      for {
        // Search with matching URL
        searchResultMatch <- transaction {
          DoobieSchedulingDao.search(
            None,
            Some(NonEmptyList.one(uri"https://spankbang.com")),
            RangeValue.all[FiniteDuration],
            RangeValue.all[Long],
            0, 10,
            SortBy.Date,
            Order.Descending,
            None, None, None
          )
        }

        _ <- IO.delay {
          searchResultMatch mustBe Seq(scheduledVideoDownload)
        }

        // Search with non-matching URL
        searchResultNoMatch <- transaction {
          DoobieSchedulingDao.search(
            None,
            Some(NonEmptyList.one(uri"https://pornhub.com")),
            RangeValue.all[FiniteDuration],
            RangeValue.all[Long],
            0, 10,
            SortBy.Date,
            Order.Descending,
            None, None, None
          )
        }

        _ <- IO.delay {
          searchResultNoMatch mustBe empty
        }
      }
      yield (): Unit
  }

  it should "search with size range max filter" in runTest {
    (scheduledVideoDownload, transaction) =>
      for {
        // Search with max size that includes the video
        searchResultMatch <- transaction {
          DoobieSchedulingDao.search(
            None, None,
            RangeValue.all[FiniteDuration],
            RangeValue[Long](None, Some(60_000)),
            0, 10,
            SortBy.Date,
            Order.Descending,
            None, None, None
          )
        }

        _ <- IO.delay {
          searchResultMatch mustBe Seq(scheduledVideoDownload)
        }

        // Search with max size that excludes the video
        searchResultNoMatch <- transaction {
          DoobieSchedulingDao.search(
            None, None,
            RangeValue.all[FiniteDuration],
            RangeValue[Long](None, Some(40_000)),
            0, 10,
            SortBy.Date,
            Order.Descending,
            None, None, None
          )
        }

        _ <- IO.delay {
          searchResultNoMatch mustBe empty
        }
      }
      yield (): Unit
  }

  it should "search with random sort order" in runTest {
    (scheduledVideoDownload, transaction) =>
      for {
        searchResult <- transaction {
          DoobieSchedulingDao.search(
            None, None,
            RangeValue.all[FiniteDuration],
            RangeValue.all[Long],
            0, 10,
            SortBy.Random,
            Order.Descending,
            None, None, None
          )
        }

        _ <- IO.delay {
          searchResult mustBe Seq(scheduledVideoDownload)
        }
      }
      yield (): Unit
  }

  it should "handle nested exception in setErrorById" in runTest {
    (scheduledVideoDownload, transaction) =>
      for {
        timestamp <- JodaClock[IO].timestamp
        rootCause = new Exception("Root cause")
        wrappedException = new RuntimeException("Wrapper exception", rootCause)

        _ <- transaction {
          DoobieSchedulingDao.setErrorById(scheduledVideoDownload.videoMetadata.id, wrappedException, timestamp)
        }

        maybeUpdated <- transaction(DoobieSchedulingDao.getById(scheduledVideoDownload.videoMetadata.id, None))

        _ <- IO.delay {
          maybeUpdated.value.errorInfo must not be empty
          maybeUpdated.value.errorInfo.value.message mustBe "Root cause"
        }
      }
      yield (): Unit
  }

}
