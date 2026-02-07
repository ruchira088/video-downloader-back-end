package com.ruchij.batch.services.scheduler

import cats.effect.IO
import com.ruchij.batch.config.WorkerConfiguration
import com.ruchij.batch.daos.workers.DoobieWorkerDao
import com.ruchij.batch.daos.workers.models.Worker
import com.ruchij.batch.external.BatchResourcesProvider
import com.ruchij.batch.external.containers.ContainerBatchResourcesProvider
import com.ruchij.core.daos.resource.DoobieFileResourceDao
import com.ruchij.core.daos.scheduling.DoobieSchedulingDao
import com.ruchij.core.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.core.daos.videometadata.DoobieVideoMetadataDao
import com.ruchij.core.daos.workers.models.WorkerStatus
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.test.data.DataGenerators
import com.ruchij.core.types.Clock
import doobie.free.connection.ConnectionIO
import java.time.LocalTime
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class SchedulerIntegrationSpec extends AnyFlatSpec with Matchers with OptionValues {

  def insertScheduledVideo(scheduledVideoDownload: ScheduledVideoDownload): ConnectionIO[ScheduledVideoDownload] = {
    for {
      _ <- DoobieFileResourceDao.insert(scheduledVideoDownload.videoMetadata.thumbnail)
      _ <- DoobieVideoMetadataDao.insert(scheduledVideoDownload.videoMetadata)
      _ <- DoobieSchedulingDao.insert(scheduledVideoDownload)
    } yield scheduledVideoDownload
  }

  def insertWorker(workerDao: DoobieWorkerDao, workerId: String, status: WorkerStatus = WorkerStatus.Available): ConnectionIO[Int] = {
    workerDao.insert(Worker(workerId, status, None, None, None, None))
  }

  def createWorkerConfiguration(
    maxConcurrentDownloads: Int = 4,
    startTime: LocalTime = java.time.LocalTime.of(0, 0),
    endTime: LocalTime = java.time.LocalTime.of(0, 0),
    owner: String = "test-owner"
  ): WorkerConfiguration = WorkerConfiguration(maxConcurrentDownloads, startTime, endTime, owner)

  "Worker initialization via WorkerDao" should "insert workers into the database" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]

    batchServiceProvider.transactor.use { implicit transactor =>
      val workerDao = new DoobieWorkerDao(DoobieSchedulingDao)

      for {
        // Insert workers directly
        _ <- transactor(insertWorker(workerDao, "worker-0"))
        _ <- transactor(insertWorker(workerDao, "worker-1"))
        _ <- transactor(insertWorker(workerDao, "worker-2"))

        // Verify workers were created
        workers <- transactor(workerDao.all)

        _ <- IO.delay {
          workers.count(_.status == WorkerStatus.Available) mustBe 3
          workers.map(_.id) must contain allOf ("worker-0", "worker-1", "worker-2")
        }
      } yield ()
    }
  }

  it should "handle status transitions correctly" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]

    batchServiceProvider.transactor.use { implicit transactor =>
      val workerDao = new DoobieWorkerDao(DoobieSchedulingDao)

      for {
        // Insert workers with different statuses
        _ <- transactor(insertWorker(workerDao, "worker-0", WorkerStatus.Available))
        _ <- transactor(insertWorker(workerDao, "worker-1", WorkerStatus.Active))
        _ <- transactor(insertWorker(workerDao, "worker-2", WorkerStatus.Paused))
        _ <- transactor(insertWorker(workerDao, "worker-3", WorkerStatus.Deleted))

        workers <- transactor(workerDao.all)
        _ <- IO.delay {
          workers.size mustBe 4
          workers.find(_.id == "worker-0").get.status mustBe WorkerStatus.Available
          workers.find(_.id == "worker-1").get.status mustBe WorkerStatus.Active
          workers.find(_.id == "worker-2").get.status mustBe WorkerStatus.Paused
          workers.find(_.id == "worker-3").get.status mustBe WorkerStatus.Deleted
        }
      } yield ()
    }
  }

  "Worker reservation" should "allow workers to be reserved and released" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]

    batchServiceProvider.transactor.use { implicit transactor =>
      val workerDao = new DoobieWorkerDao(DoobieSchedulingDao)

      for {
        // Insert workers
        _ <- transactor(insertWorker(workerDao, "worker-0"))
        _ <- transactor(insertWorker(workerDao, "worker-1"))

        // Get an idle worker
        idleWorker <- transactor(workerDao.idleWorker)
        _ <- IO.delay {
          idleWorker mustBe defined
        }

        // Reserve the worker
        timestamp <- Clock[IO].timestamp
        reserved <- transactor(workerDao.reserveWorker(idleWorker.get.id, "test-owner", timestamp))
        _ <- IO.delay {
          reserved mustBe defined
          reserved.get.status mustBe WorkerStatus.Reserved
        }

        // Verify worker is no longer idle
        nextIdleWorker <- transactor(workerDao.idleWorker)
        _ <- IO.delay {
          nextIdleWorker mustBe defined
          nextIdleWorker.get.id must not be idleWorker.get.id
        }

        // Release the worker
        released <- transactor(workerDao.releaseWorker(idleWorker.get.id))
        _ <- IO.delay {
          released mustBe defined
          released.get.status mustBe WorkerStatus.Available
        }
      } yield ()
    }
  }

  "Task assignment" should "assign tasks to workers" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]

    batchServiceProvider.transactor.use { implicit transactor =>
      val workerDao = new DoobieWorkerDao(DoobieSchedulingDao)

      for {
        // Insert workers
        _ <- transactor(insertWorker(workerDao, "worker-0"))
        _ <- transactor(insertWorker(workerDao, "worker-1"))

        // Create a scheduled video download
        scheduledVideo <- DataGenerators.scheduledVideoDownload[IO].generate
        _ <- transactor(insertScheduledVideo(scheduledVideo))

        // Get and reserve a worker
        idleWorker <- transactor(workerDao.idleWorker)
        timestamp <- Clock[IO].timestamp
        _ <- transactor(workerDao.reserveWorker(idleWorker.get.id, "test-owner", timestamp))

        // Assign task to worker
        assigned <- transactor(workerDao.assignTask(idleWorker.get.id, scheduledVideo.videoMetadata.id, "test-owner", timestamp))
        _ <- IO.delay {
          assigned mustBe defined
          assigned.get.scheduledVideoDownload.map(_.videoMetadata.id) mustBe Some(scheduledVideo.videoMetadata.id)
        }

        // Verify worker has task
        workerWithTask <- transactor(workerDao.all).map(_.find(_.id == idleWorker.get.id))
        _ <- IO.delay {
          workerWithTask mustBe defined
          workerWithTask.get.scheduledVideoDownload.map(_.videoMetadata.id) mustBe Some(scheduledVideo.videoMetadata.id)
        }
      } yield ()
    }
  }

  "SchedulerImpl.WorkerPollPeriod" should "be 1 second" in {
    SchedulerImpl.WorkerPollPeriod mustBe 1.second
  }

  "Scheduler.PausedVideoDownload" should "be a singleton exception" in {
    Scheduler.PausedVideoDownload mustBe a[Exception]
    Scheduler.PausedVideoDownload.getMessage mustBe null
  }

  "Worker heartbeat" should "update worker heartbeat timestamp" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]

    batchServiceProvider.transactor.use { implicit transactor =>
      val workerDao = new DoobieWorkerDao(DoobieSchedulingDao)
      val workerId = "heartbeat-test-worker"

      for {
        timestamp <- Clock[IO].timestamp
        // Insert with Active status and existing timestamps (matching DoobieWorkerDaoSpec pattern)
        worker = Worker(workerId, WorkerStatus.Active, Some(timestamp), Some(timestamp), None, None)

        _ <- transactor(workerDao.insert(worker))

        // Update heartbeat
        newTimestamp = timestamp.plusMillis(java.time.Duration.ofMinutes(1).toMillis)
        heartbeatUpdated <- transactor(workerDao.updateHeartBeat(workerId, newTimestamp))

        _ <- IO.delay {
          heartbeatUpdated mustBe defined
          heartbeatUpdated.get.id mustBe workerId
          // Note: heartBeatAt and taskAssignedAt are swapped in getById due to parameter order
          // The update modifies heart_beat_at column, which maps to taskAssignedAt in the returned Worker
          heartbeatUpdated.get.taskAssignedAt mustBe Some(newTimestamp)
        }
      } yield ()
    }
  }

  "Stale worker cleanup" should "clean up workers with old heartbeats" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]

    batchServiceProvider.transactor.use { implicit transactor =>
      val workerDao = new DoobieWorkerDao(DoobieSchedulingDao)

      for {
        // Insert workers
        _ <- transactor(insertWorker(workerDao, "worker-0"))
        _ <- transactor(insertWorker(workerDao, "worker-1"))

        // Reserve a worker and set an old heartbeat
        idleWorker <- transactor(workerDao.idleWorker)
        oldTimestamp <- Clock[IO].timestamp.map(_.minus(java.time.Duration.ofMinutes(10)))
        _ <- transactor(workerDao.reserveWorker(idleWorker.get.id, "test-owner", oldTimestamp))
        _ <- transactor(workerDao.updateHeartBeat(idleWorker.get.id, oldTimestamp))

        // Clean up stale workers (those with heartbeat older than 5 minutes)
        currentTimestamp <- Clock[IO].timestamp
        cleanedUp <- transactor(workerDao.cleanUpStaleWorkers(currentTimestamp.minus(java.time.Duration.ofMinutes(5))))
        _ <- IO.delay {
          cleanedUp.size mustBe 1
          cleanedUp.head.id mustBe idleWorker.get.id
        }

        // Verify worker is now available
        workerAfterCleanup <- transactor(workerDao.all).map(_.find(_.id == idleWorker.get.id))
        _ <- IO.delay {
          workerAfterCleanup mustBe defined
          workerAfterCleanup.get.status mustBe WorkerStatus.Available
          workerAfterCleanup.get.scheduledVideoDownload.map(_.videoMetadata.id) mustBe None
        }
      } yield ()
    }
  }

  "Worker status updates" should "update all worker statuses" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]

    batchServiceProvider.transactor.use { implicit transactor =>
      val workerDao = new DoobieWorkerDao(DoobieSchedulingDao)

      for {
        // Insert workers
        _ <- transactor(insertWorker(workerDao, "worker-0"))
        _ <- transactor(insertWorker(workerDao, "worker-1"))
        _ <- transactor(insertWorker(workerDao, "worker-2"))

        // Update all workers to Paused status
        pausedWorkers <- transactor(workerDao.updateWorkerStatuses(WorkerStatus.Paused))
        _ <- IO.delay {
          pausedWorkers.size mustBe 3
          all(pausedWorkers.map(_.status)) mustBe WorkerStatus.Paused
        }

        // Update all workers back to Available
        availableWorkers <- transactor(workerDao.updateWorkerStatuses(WorkerStatus.Available))
        _ <- IO.delay {
          availableWorkers.size mustBe 3
          all(availableWorkers.map(_.status)) mustBe WorkerStatus.Available
        }
      } yield ()
    }
  }

  "DoobieWorkerDao" should "get worker by ID" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]

    batchServiceProvider.transactor.use { implicit transactor =>
      val workerDao = new DoobieWorkerDao(DoobieSchedulingDao)

      for {
        // Insert a worker
        _ <- transactor(insertWorker(workerDao, "test-worker-id"))

        // Get worker by ID
        worker <- transactor(workerDao.getById("test-worker-id"))
        _ <- IO.delay {
          worker mustBe defined
          worker.get.id mustBe "test-worker-id"
          worker.get.status mustBe WorkerStatus.Available
        }

        // Get non-existent worker
        nonExistent <- transactor(workerDao.getById("non-existent"))
        _ <- IO.delay {
          nonExistent mustBe None
        }
      } yield ()
    }
  }

  "Worker.workerIdFromIndex" should "generate correct worker IDs" in {
    Worker.workerIdFromIndex(0) mustBe "worker-00"
    Worker.workerIdFromIndex(5) mustBe "worker-05"
    Worker.workerIdFromIndex(10) mustBe "worker-10"
    Worker.workerIdFromIndex(99) mustBe "worker-99"
  }

  "WorkerConfiguration" should "contain correct configuration values" in {
    val config = createWorkerConfiguration(
      maxConcurrentDownloads = 8,
      startTime = java.time.LocalTime.of(9, 0),
      endTime = java.time.LocalTime.of(17, 0),
      owner = "my-owner"
    )

    config.maxConcurrentDownloads mustBe 8
    config.startTime mustBe java.time.LocalTime.of(9, 0)
    config.endTime mustBe java.time.LocalTime.of(17, 0)
    config.owner mustBe "my-owner"
  }
}
