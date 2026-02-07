package com.ruchij.batch.daos.filesync

import cats.effect.IO
import cats.~>
import com.ruchij.batch.daos.filesync.models.FileSync
import com.ruchij.core.external.embedded.EmbeddedCoreResourcesProvider
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.types.Clock
import doobie.ConnectionIO
import java.time.Instant
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.ExecutionContext.Implicits.global

class DoobieFileSyncDaoSpec extends AnyFlatSpec with Matchers with OptionValues {

  case class TestFixture(
    transaction: ConnectionIO ~> IO,
    timestamp: Instant
  )

  def runTest(testFn: TestFixture => IO[Unit]): Unit =
    runIO {
      new EmbeddedCoreResourcesProvider[IO].transactor.use { transaction =>
        for {
          timestamp <- Clock[IO].timestamp
          result <- testFn(TestFixture(transaction, timestamp))
        } yield result
      }
    }

  "insert" should "insert a file sync record" in runTest { fixture =>
    val fileSync = FileSync(fixture.timestamp, "/path/to/file.mp4", None)

    for {
      insertResult <- fixture.transaction(DoobieFileSyncDao.insert(fileSync))
      _ <- IO.delay { insertResult mustBe 1 }
    } yield ()
  }

  it should "insert multiple file sync records with different paths" in runTest { fixture =>
    val fileSync1 = FileSync(fixture.timestamp, "/path/to/file1.mp4", None)
    val fileSync2 = FileSync(fixture.timestamp, "/path/to/file2.mp4", None)

    for {
      insertResult1 <- fixture.transaction(DoobieFileSyncDao.insert(fileSync1))
      insertResult2 <- fixture.transaction(DoobieFileSyncDao.insert(fileSync2))
      _ <- IO.delay {
        insertResult1 mustBe 1
        insertResult2 mustBe 1
      }
    } yield ()
  }

  "findByPath" should "find an existing file sync record" in runTest { fixture =>
    val path = "/path/to/video.mp4"
    val fileSync = FileSync(fixture.timestamp, path, None)

    for {
      _ <- fixture.transaction(DoobieFileSyncDao.insert(fileSync))

      maybeFileSync <- fixture.transaction(DoobieFileSyncDao.findByPath(path))
      _ <- IO.delay {
        maybeFileSync mustBe defined
        maybeFileSync.value.path mustBe path
        maybeFileSync.value.lockedAt mustBe fixture.timestamp
        maybeFileSync.value.syncedAt mustBe None
      }
    } yield ()
  }

  it should "return None for non-existent path" in runTest { fixture =>
    for {
      maybeFileSync <- fixture.transaction(DoobieFileSyncDao.findByPath("/non/existent/path.mp4"))
      _ <- IO.delay { maybeFileSync mustBe None }
    } yield ()
  }

  it should "find file sync record with synced timestamp" in runTest { fixture =>
    val path = "/path/to/synced-video.mp4"
    val fileSync = FileSync(fixture.timestamp, path, None)

    for {
      _ <- fixture.transaction(DoobieFileSyncDao.insert(fileSync))

      syncedTimestamp = fixture.timestamp.plusMillis(java.time.Duration.ofMinutes(5).toMillis)
      _ <- fixture.transaction(DoobieFileSyncDao.complete(path, syncedTimestamp))

      maybeFileSync <- fixture.transaction(DoobieFileSyncDao.findByPath(path))
      _ <- IO.delay {
        maybeFileSync mustBe defined
        maybeFileSync.value.syncedAt mustBe Some(syncedTimestamp)
      }
    } yield ()
  }

  "complete" should "mark a file sync as complete" in runTest { fixture =>
    val path = "/path/to/completing-video.mp4"
    val fileSync = FileSync(fixture.timestamp, path, None)

    for {
      _ <- fixture.transaction(DoobieFileSyncDao.insert(fileSync))

      syncedTimestamp = fixture.timestamp.plusMillis(java.time.Duration.ofMinutes(10).toMillis)
      maybeCompletedFileSync <- fixture.transaction(DoobieFileSyncDao.complete(path, syncedTimestamp))
      _ <- IO.delay {
        maybeCompletedFileSync mustBe defined
        maybeCompletedFileSync.value.path mustBe path
        maybeCompletedFileSync.value.syncedAt mustBe Some(syncedTimestamp)
        maybeCompletedFileSync.value.lockedAt mustBe fixture.timestamp
      }
    } yield ()
  }

  it should "return None when completing non-existent path" in runTest { fixture =>
    for {
      maybeCompletedFileSync <- fixture.transaction(
        DoobieFileSyncDao.complete("/non/existent/path.mp4", fixture.timestamp)
      )
      _ <- IO.delay { maybeCompletedFileSync mustBe None }
    } yield ()
  }

  it should "update synced timestamp on already completed file" in runTest { fixture =>
    val path = "/path/to/re-sync-video.mp4"
    val fileSync = FileSync(fixture.timestamp, path, None)

    for {
      _ <- fixture.transaction(DoobieFileSyncDao.insert(fileSync))

      firstSyncTimestamp = fixture.timestamp.plusMillis(java.time.Duration.ofMinutes(5).toMillis)
      _ <- fixture.transaction(DoobieFileSyncDao.complete(path, firstSyncTimestamp))

      secondSyncTimestamp = fixture.timestamp.plusMillis(java.time.Duration.ofMinutes(15).toMillis)
      maybeUpdatedFileSync <- fixture.transaction(DoobieFileSyncDao.complete(path, secondSyncTimestamp))
      _ <- IO.delay {
        maybeUpdatedFileSync mustBe defined
        maybeUpdatedFileSync.value.syncedAt mustBe Some(secondSyncTimestamp)
      }
    } yield ()
  }

  "deleteByPath" should "delete an existing file sync record" in runTest { fixture =>
    val path = "/path/to/delete-video.mp4"
    val fileSync = FileSync(fixture.timestamp, path, None)

    for {
      _ <- fixture.transaction(DoobieFileSyncDao.insert(fileSync))

      maybeDeletedFileSync <- fixture.transaction(DoobieFileSyncDao.deleteByPath(path))
      _ <- IO.delay {
        maybeDeletedFileSync mustBe defined
        maybeDeletedFileSync.value.path mustBe path
      }

      maybeFileSync <- fixture.transaction(DoobieFileSyncDao.findByPath(path))
      _ <- IO.delay { maybeFileSync mustBe None }
    } yield ()
  }

  it should "return None when deleting non-existent path" in runTest { fixture =>
    for {
      maybeDeletedFileSync <- fixture.transaction(DoobieFileSyncDao.deleteByPath("/non/existent/path.mp4"))
      _ <- IO.delay { maybeDeletedFileSync mustBe None }
    } yield ()
  }

  it should "delete completed file sync record" in runTest { fixture =>
    val path = "/path/to/completed-delete-video.mp4"
    val fileSync = FileSync(fixture.timestamp, path, None)

    for {
      _ <- fixture.transaction(DoobieFileSyncDao.insert(fileSync))
      _ <- fixture.transaction(DoobieFileSyncDao.complete(path, fixture.timestamp.plusMillis(java.time.Duration.ofMinutes(5).toMillis)))

      maybeDeletedFileSync <- fixture.transaction(DoobieFileSyncDao.deleteByPath(path))
      _ <- IO.delay {
        maybeDeletedFileSync mustBe defined
        maybeDeletedFileSync.value.syncedAt mustBe defined
      }

      maybeFileSync <- fixture.transaction(DoobieFileSyncDao.findByPath(path))
      _ <- IO.delay { maybeFileSync mustBe None }
    } yield ()
  }

  "full lifecycle" should "handle insert, complete, and delete operations" in runTest { fixture =>
    val path = "/path/to/lifecycle-video.mp4"

    for {
      maybeInitial <- fixture.transaction(DoobieFileSyncDao.findByPath(path))
      _ <- IO.delay { maybeInitial mustBe None }

      fileSync = FileSync(fixture.timestamp, path, None)
      _ <- fixture.transaction(DoobieFileSyncDao.insert(fileSync))

      maybeInserted <- fixture.transaction(DoobieFileSyncDao.findByPath(path))
      _ <- IO.delay {
        maybeInserted mustBe defined
        maybeInserted.value.syncedAt mustBe None
      }

      syncedTimestamp = fixture.timestamp.plusMillis(java.time.Duration.ofMinutes(10).toMillis)
      maybeCompleted <- fixture.transaction(DoobieFileSyncDao.complete(path, syncedTimestamp))
      _ <- IO.delay {
        maybeCompleted mustBe defined
        maybeCompleted.value.syncedAt mustBe Some(syncedTimestamp)
      }

      maybeDeleted <- fixture.transaction(DoobieFileSyncDao.deleteByPath(path))
      _ <- IO.delay { maybeDeleted mustBe defined }

      maybeFinal <- fixture.transaction(DoobieFileSyncDao.findByPath(path))
      _ <- IO.delay { maybeFinal mustBe None }
    } yield ()
  }

  "concurrent operations" should "handle multiple paths independently" in runTest { fixture =>
    val path1 = "/path/to/video1.mp4"
    val path2 = "/path/to/video2.mp4"
    val path3 = "/path/to/video3.mp4"

    for {
      _ <- fixture.transaction(DoobieFileSyncDao.insert(FileSync(fixture.timestamp, path1, None)))
      _ <- fixture.transaction(DoobieFileSyncDao.insert(FileSync(fixture.timestamp, path2, None)))
      _ <- fixture.transaction(DoobieFileSyncDao.insert(FileSync(fixture.timestamp, path3, None)))

      _ <- fixture.transaction(DoobieFileSyncDao.complete(path1, fixture.timestamp.plusMillis(java.time.Duration.ofMinutes(5).toMillis)))
      _ <- fixture.transaction(DoobieFileSyncDao.deleteByPath(path2))

      maybeFile1 <- fixture.transaction(DoobieFileSyncDao.findByPath(path1))
      maybeFile2 <- fixture.transaction(DoobieFileSyncDao.findByPath(path2))
      maybeFile3 <- fixture.transaction(DoobieFileSyncDao.findByPath(path3))

      _ <- IO.delay {
        maybeFile1 mustBe defined
        maybeFile1.value.syncedAt mustBe defined
        maybeFile2 mustBe None
        maybeFile3 mustBe defined
        maybeFile3.value.syncedAt mustBe None
      }
    } yield ()
  }
}
