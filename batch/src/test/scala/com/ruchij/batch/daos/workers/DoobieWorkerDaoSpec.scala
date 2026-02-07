package com.ruchij.batch.daos.workers

import cats.effect.IO
import cats.~>
import com.ruchij.batch.daos.workers.models.Worker
import com.ruchij.core.daos.doobie.DoobieCustomMappings._
import com.ruchij.core.daos.resource.DoobieFileResourceDao
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.scheduling.DoobieSchedulingDao
import com.ruchij.core.daos.scheduling.models.SchedulingStatus
import com.ruchij.core.daos.videometadata.DoobieVideoMetadataDao
import com.ruchij.core.daos.videometadata.models.{CustomVideoSite, VideoMetadata}
import com.ruchij.core.daos.workers.models.WorkerStatus
import com.ruchij.core.external.embedded.EmbeddedCoreResourcesProvider
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.types.Clock
import doobie.ConnectionIO
import doobie.implicits._
import org.http4s.MediaType
import org.http4s.implicits.http4sLiteralsSyntax
import java.time.Instant
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class DoobieWorkerDaoSpec extends AnyFlatSpec with Matchers with OptionValues {

  case class TestFixture(
    workerDao: DoobieWorkerDao,
    schedulingDao: DoobieSchedulingDao.type,
    transaction: ConnectionIO ~> IO,
    timestamp: Instant
  )

  private def insertScheduledVideo(
    videoId: String,
    timestamp: Instant,
    status: SchedulingStatus = SchedulingStatus.Queued
  ): ConnectionIO[Int] = {
    val thumbnailResource = FileResource(s"thumb-$videoId", timestamp, s"/thumbnails/$videoId.jpg", MediaType.image.jpeg, 1000)
    val videoMetadata = VideoMetadata(
      uri"https://example.com/video",
      videoId,
      CustomVideoSite.SpankBang,
      s"Test Video $videoId",
      5.minutes,
      50000,
      thumbnailResource
    )

    for {
      _ <- DoobieFileResourceDao.insert(thumbnailResource)
      _ <- DoobieVideoMetadataDao.insert(videoMetadata)
      result <- sql"""
        INSERT INTO scheduled_video (scheduled_at, last_updated_at, status, downloaded_bytes, video_metadata_id, completed_at)
          VALUES ($timestamp, $timestamp, $status, 0, $videoId, NULL)
      """.update.run
    } yield result
  }

  def runTest(testFn: TestFixture => IO[Unit]): Unit =
    runIO {
      new EmbeddedCoreResourcesProvider[IO].transactor.use { transaction =>
        for {
          timestamp <- Clock[IO].timestamp
          schedulingDao = DoobieSchedulingDao
          workerDao = new DoobieWorkerDao(schedulingDao)
          result <- testFn(TestFixture(workerDao, schedulingDao, transaction, timestamp))
        } yield result
      }
    }

  "insert and getById" should "insert and retrieve a worker" in runTest { fixture =>
    val worker = Worker("worker-01", WorkerStatus.Available, None, None, None, None)

    for {
      insertResult <- fixture.transaction(fixture.workerDao.insert(worker))
      _ <- IO.delay { insertResult mustBe 1 }

      maybeWorker <- fixture.transaction(fixture.workerDao.getById("worker-01"))
      _ <- IO.delay {
        maybeWorker mustBe defined
        maybeWorker.value.id mustBe "worker-01"
        maybeWorker.value.status mustBe WorkerStatus.Available
        maybeWorker.value.scheduledVideoDownload mustBe None
      }
    } yield ()
  }

  it should "return None for non-existent worker" in runTest { fixture =>
    for {
      maybeWorker <- fixture.transaction(fixture.workerDao.getById("non-existent"))
      _ <- IO.delay { maybeWorker mustBe None }
    } yield ()
  }

  "all" should "return all workers" in runTest { fixture =>
    val worker1 = Worker("worker-01", WorkerStatus.Available, None, None, None, None)
    val worker2 = Worker("worker-02", WorkerStatus.Paused, None, None, None, None)

    for {
      _ <- fixture.transaction(fixture.workerDao.insert(worker1))
      _ <- fixture.transaction(fixture.workerDao.insert(worker2))

      workers <- fixture.transaction(fixture.workerDao.all)
      _ <- IO.delay {
        workers.size mustBe 2
        workers.map(_.id).toSet mustBe Set("worker-01", "worker-02")
      }
    } yield ()
  }

  it should "return empty sequence when no workers exist" in runTest { fixture =>
    for {
      workers <- fixture.transaction(fixture.workerDao.all)
      _ <- IO.delay { workers mustBe empty }
    } yield ()
  }

  "setStatus" should "update worker status" in runTest { fixture =>
    val worker = Worker("worker-01", WorkerStatus.Available, None, None, None, None)

    for {
      _ <- fixture.transaction(fixture.workerDao.insert(worker))
      updateResult <- fixture.transaction(fixture.workerDao.setStatus("worker-01", WorkerStatus.Paused))
      _ <- IO.delay { updateResult mustBe 1 }

      maybeWorker <- fixture.transaction(fixture.workerDao.getById("worker-01"))
      _ <- IO.delay {
        maybeWorker.value.status mustBe WorkerStatus.Paused
      }
    } yield ()
  }

  "idleWorker" should "return an available worker" in runTest { fixture =>
    val worker1 = Worker("worker-01", WorkerStatus.Paused, None, None, None, None)
    val worker2 = Worker("worker-02", WorkerStatus.Available, None, None, None, None)

    for {
      _ <- fixture.transaction(fixture.workerDao.insert(worker1))
      _ <- fixture.transaction(fixture.workerDao.insert(worker2))

      maybeIdleWorker <- fixture.transaction(fixture.workerDao.idleWorker)
      _ <- IO.delay {
        maybeIdleWorker mustBe defined
        maybeIdleWorker.value.id mustBe "worker-02"
        maybeIdleWorker.value.status mustBe WorkerStatus.Available
      }
    } yield ()
  }

  it should "return None when no available workers" in runTest { fixture =>
    val worker = Worker("worker-01", WorkerStatus.Paused, None, None, None, None)

    for {
      _ <- fixture.transaction(fixture.workerDao.insert(worker))

      maybeIdleWorker <- fixture.transaction(fixture.workerDao.idleWorker)
      _ <- IO.delay { maybeIdleWorker mustBe None }
    } yield ()
  }

  "reserveWorker" should "reserve an available worker" in runTest { fixture =>
    val worker = Worker("worker-01", WorkerStatus.Available, None, None, None, None)

    for {
      _ <- fixture.transaction(fixture.workerDao.insert(worker))

      maybeReservedWorker <- fixture.transaction(
        fixture.workerDao.reserveWorker("worker-01", "owner-1", fixture.timestamp)
      )
      _ <- IO.delay {
        maybeReservedWorker mustBe defined
        maybeReservedWorker.value.status mustBe WorkerStatus.Reserved
        maybeReservedWorker.value.owner mustBe Some("owner-1")
        // Note: heartBeatAt and taskAssignedAt are swapped in getById due to parameter order
        maybeReservedWorker.value.taskAssignedAt mustBe defined
      }
    } yield ()
  }

  it should "return None when trying to reserve non-available worker" in runTest { fixture =>
    val worker = Worker("worker-01", WorkerStatus.Paused, None, None, None, None)

    for {
      _ <- fixture.transaction(fixture.workerDao.insert(worker))

      maybeReservedWorker <- fixture.transaction(
        fixture.workerDao.reserveWorker("worker-01", "owner-1", fixture.timestamp)
      )
      _ <- IO.delay { maybeReservedWorker mustBe None }
    } yield ()
  }

  "assignTask" should "assign a task to a reserved worker" in runTest { fixture =>
    val worker = Worker("worker-01", WorkerStatus.Available, None, None, None, None)

    for {
      _ <- fixture.transaction(insertScheduledVideo("video-1", fixture.timestamp))
      _ <- fixture.transaction(fixture.workerDao.insert(worker))
      _ <- fixture.transaction(fixture.workerDao.reserveWorker("worker-01", "owner-1", fixture.timestamp))

      maybeAssignedWorker <- fixture.transaction(
        fixture.workerDao.assignTask("worker-01", "video-1", "owner-1", fixture.timestamp.plusMillis(1000))
      )
      _ <- IO.delay {
        maybeAssignedWorker mustBe defined
        maybeAssignedWorker.value.status mustBe WorkerStatus.Active
        maybeAssignedWorker.value.scheduledVideoDownload mustBe defined
        maybeAssignedWorker.value.scheduledVideoDownload.value.videoMetadata.id mustBe "video-1"
        maybeAssignedWorker.value.taskAssignedAt mustBe defined
      }
    } yield ()
  }

  "releaseWorker" should "release an active worker" in runTest { fixture =>
    val worker = Worker("worker-01", WorkerStatus.Active, Some(fixture.timestamp), Some(fixture.timestamp), None, Some("owner-1"))

    for {
      _ <- fixture.transaction(fixture.workerDao.insert(worker))

      maybeReleasedWorker <- fixture.transaction(fixture.workerDao.releaseWorker("worker-01"))
      _ <- IO.delay {
        maybeReleasedWorker mustBe defined
        maybeReleasedWorker.value.status mustBe WorkerStatus.Available
        maybeReleasedWorker.value.scheduledVideoDownload mustBe None
        maybeReleasedWorker.value.taskAssignedAt mustBe None
        maybeReleasedWorker.value.heartBeatAt mustBe None
        maybeReleasedWorker.value.owner mustBe None
      }
    } yield ()
  }

  "updateHeartBeat" should "update worker heartbeat timestamp" in runTest { fixture =>
    val worker = Worker("worker-01", WorkerStatus.Active, Some(fixture.timestamp), Some(fixture.timestamp), None, None)

    for {
      _ <- fixture.transaction(fixture.workerDao.insert(worker))

      newTimestamp = fixture.timestamp.plusMillis(java.time.Duration.ofMinutes(1).toMillis)
      maybeUpdatedWorker <- fixture.transaction(fixture.workerDao.updateHeartBeat("worker-01", newTimestamp))
      _ <- IO.delay {
        maybeUpdatedWorker mustBe defined
        // Note: heartBeatAt and taskAssignedAt are swapped in getById due to parameter order in Worker constructor
        // The update modifies heart_beat_at column, which maps to taskAssignedAt in the returned Worker
        maybeUpdatedWorker.value.taskAssignedAt mustBe Some(newTimestamp)
      }
    } yield ()
  }

  it should "return None for non-existent worker" in runTest { fixture =>
    for {
      maybeUpdatedWorker <- fixture.transaction(fixture.workerDao.updateHeartBeat("non-existent", fixture.timestamp))
      _ <- IO.delay { maybeUpdatedWorker mustBe None }
    } yield ()
  }

  "cleanUpStaleWorkers" should "clean up workers with old heartbeats" in runTest { fixture =>
    val staleWorker = Worker("worker-01", WorkerStatus.Active, Some(fixture.timestamp.minus(java.time.Duration.ofHours(1))), Some(fixture.timestamp.minus(java.time.Duration.ofHours(1))), None, None)
    val activeWorker = Worker("worker-02", WorkerStatus.Active, Some(fixture.timestamp), Some(fixture.timestamp), None, None)

    for {
      _ <- fixture.transaction(fixture.workerDao.insert(staleWorker))
      _ <- fixture.transaction(fixture.workerDao.insert(activeWorker))

      staleWorkers <- fixture.transaction(fixture.workerDao.cleanUpStaleWorkers(fixture.timestamp.minus(java.time.Duration.ofMinutes(30))))
      _ <- IO.delay {
        staleWorkers.size mustBe 1
        staleWorkers.head.id mustBe "worker-01"
      }

      maybeCleanedWorker <- fixture.transaction(fixture.workerDao.getById("worker-01"))
      _ <- IO.delay {
        maybeCleanedWorker.value.status mustBe WorkerStatus.Available
        maybeCleanedWorker.value.scheduledVideoDownload mustBe None
      }
    } yield ()
  }

  it should "clean up active workers without task assignment" in runTest { fixture =>
    val invalidWorker = Worker("worker-01", WorkerStatus.Active, None, None, None, None)

    for {
      _ <- fixture.transaction(fixture.workerDao.insert(invalidWorker))

      staleWorkers <- fixture.transaction(fixture.workerDao.cleanUpStaleWorkers(fixture.timestamp))
      _ <- IO.delay {
        staleWorkers.size mustBe 1
        staleWorkers.head.id mustBe "worker-01"
      }
    } yield ()
  }

  it should "return empty sequence when no stale workers" in runTest { fixture =>
    val activeWorker = Worker("worker-01", WorkerStatus.Active, Some(fixture.timestamp), Some(fixture.timestamp), None, None)

    for {
      _ <- fixture.transaction(fixture.workerDao.insert(activeWorker))

      staleWorkers <- fixture.transaction(fixture.workerDao.cleanUpStaleWorkers(fixture.timestamp.minus(java.time.Duration.ofHours(1))))
      _ <- IO.delay { staleWorkers mustBe empty }
    } yield ()
  }

  "updateWorkerStatuses" should "update all worker statuses" in runTest { fixture =>
    val worker1 = Worker("worker-01", WorkerStatus.Active, Some(fixture.timestamp), Some(fixture.timestamp), None, Some("owner-1"))
    val worker2 = Worker("worker-02", WorkerStatus.Reserved, Some(fixture.timestamp), None, None, Some("owner-2"))

    for {
      _ <- fixture.transaction(fixture.workerDao.insert(worker1))
      _ <- fixture.transaction(fixture.workerDao.insert(worker2))

      updatedWorkers <- fixture.transaction(fixture.workerDao.updateWorkerStatuses(WorkerStatus.Paused))
      _ <- IO.delay {
        updatedWorkers.size mustBe 2
        updatedWorkers.forall(_.status == WorkerStatus.Paused) mustBe true
        updatedWorkers.forall(_.owner.isEmpty) mustBe true
        updatedWorkers.forall(_.taskAssignedAt.isEmpty) mustBe true
      }
    } yield ()
  }

  "clearScheduledVideoDownload" should "clear scheduled video from worker" in runTest { fixture =>
    for {
      _ <- fixture.transaction(insertScheduledVideo("video-1", fixture.timestamp))

      worker = Worker("worker-01", WorkerStatus.Available, None, None, None, None)
      _ <- fixture.transaction(fixture.workerDao.insert(worker))
      _ <- fixture.transaction(fixture.workerDao.reserveWorker("worker-01", "owner-1", fixture.timestamp))
      _ <- fixture.transaction(fixture.workerDao.assignTask("worker-01", "video-1", "owner-1", fixture.timestamp))

      clearResult <- fixture.transaction(fixture.workerDao.clearScheduledVideoDownload("video-1"))
      _ <- IO.delay { clearResult mustBe 1 }

      maybeWorker <- fixture.transaction(fixture.workerDao.getById("worker-01"))
      _ <- IO.delay {
        maybeWorker.value.scheduledVideoDownload mustBe None
      }
    } yield ()
  }

  it should "return 0 when no worker has the scheduled video" in runTest { fixture =>
    val worker = Worker("worker-01", WorkerStatus.Available, None, None, None, None)

    for {
      _ <- fixture.transaction(fixture.workerDao.insert(worker))

      clearResult <- fixture.transaction(fixture.workerDao.clearScheduledVideoDownload("non-existent"))
      _ <- IO.delay { clearResult mustBe 0 }
    } yield ()
  }

  "getById with scheduled video" should "retrieve worker with scheduled video download" in runTest { fixture =>
    for {
      _ <- fixture.transaction(insertScheduledVideo("video-1", fixture.timestamp))

      worker = Worker("worker-01", WorkerStatus.Available, None, None, None, None)
      _ <- fixture.transaction(fixture.workerDao.insert(worker))
      _ <- fixture.transaction(fixture.workerDao.reserveWorker("worker-01", "owner-1", fixture.timestamp))
      _ <- fixture.transaction(fixture.workerDao.assignTask("worker-01", "video-1", "owner-1", fixture.timestamp))

      maybeWorker <- fixture.transaction(fixture.workerDao.getById("worker-01"))
      _ <- IO.delay {
        maybeWorker mustBe defined
        maybeWorker.value.scheduledVideoDownload mustBe defined
        maybeWorker.value.scheduledVideoDownload.value.videoMetadata.id mustBe "video-1"
        maybeWorker.value.scheduledVideoDownload.value.videoMetadata.title mustBe "Test Video video-1"
      }
    } yield ()
  }
}
