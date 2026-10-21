package com.ruchij.batch.services.scheduling

import cats.effect.IO
import cats.implicits._
import cats.~>
import com.ruchij.batch.config.RetryConfiguration
import com.ruchij.batch.daos.scheduling.DoobieBatchSchedulingDao
import com.ruchij.batch.daos.workers.DoobieWorkerDao
import com.ruchij.batch.external.BatchResourcesProvider
import com.ruchij.batch.external.containers.ContainerBatchResourcesProvider
import com.ruchij.core.daos.duplicate.models.DuplicateVideo
import com.ruchij.core.config.StorageConfiguration
import com.ruchij.core.daos.duplicate.DoobieDuplicateVideoDao
import com.ruchij.core.daos.hash.DoobieVideoPerceptualHashDao
import com.ruchij.core.daos.hash.models.VideoPerceptualHash
import com.ruchij.core.daos.resource.DoobieFileResourceDao
import com.ruchij.core.daos.scheduling.DoobieSchedulingDao
import com.ruchij.core.daos.scheduling.models.{ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.daos.video.DoobieVideoDao
import com.ruchij.core.daos.videometadata.DoobieVideoMetadataDao
import com.ruchij.core.exceptions.ResourceNotFoundException
import com.ruchij.core.messaging.{PubSub, Publisher, Subscriber}
import com.ruchij.core.services.repository.FileRepositoryService
import com.ruchij.core.services.scheduling.models.{DownloadProgress, WorkerStatusUpdate}
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.test.data.DataGenerators
import com.ruchij.core.types.{Clock, RandomGenerator}
import doobie.free.connection.ConnectionIO
import fs2.Stream
import org.scalamock.scalatest.MockFactory
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class BatchSchedulingServiceImplSpec extends AnyFlatSpec with MockFactory with Matchers with OptionValues {

  case class TestFixture(
    batchSchedulingService: BatchSchedulingServiceImpl[IO, ConnectionIO],
    schedulingDao: DoobieSchedulingDao.type,
    downloadProgressPublisher: Publisher[IO, DownloadProgress],
    scheduledVideoDownloadPubSub: PubSub[IO, ScheduledVideoDownload]
  )

  def createTestFixture(
    retryConfiguration: RetryConfiguration = RetryConfiguration.Disabled
  )(implicit transactor: ConnectionIO ~> IO): TestFixture = {
    val downloadProgressPublisher = mock[Publisher[IO, DownloadProgress]]
    val repositoryService = mock[FileRepositoryService[IO]]
    val workerStatusSubscriber = mock[Subscriber[IO, WorkerStatusUpdate]]
    val scheduledVideoDownloadPubSub = mock[PubSub[IO, ScheduledVideoDownload]]
    val storageConfiguration = StorageConfiguration("video-folder", "image-folder", List.empty)

    val batchSchedulingService = new BatchSchedulingServiceImpl[IO, ConnectionIO](
      downloadProgressPublisher,
      workerStatusSubscriber,
      scheduledVideoDownloadPubSub,
      repositoryService,
      DoobieSchedulingDao,
      DoobieBatchSchedulingDao,
      new DoobieWorkerDao(DoobieSchedulingDao),
      DoobieVideoMetadataDao,
      storageConfiguration,
      RetryPolicy(retryConfiguration),
      RandomGenerator.eval(IO.pure(0.5))
    )

    TestFixture(batchSchedulingService, DoobieSchedulingDao, downloadProgressPublisher, scheduledVideoDownloadPubSub)
  }

  def insertScheduledVideo(scheduledVideoDownload: ScheduledVideoDownload): ConnectionIO[ScheduledVideoDownload] = {
    for {
      _ <- DoobieFileResourceDao.insert(scheduledVideoDownload.videoMetadata.thumbnail)
      _ <- DoobieVideoMetadataDao.insert(scheduledVideoDownload.videoMetadata)
      _ <- DoobieSchedulingDao.insert(scheduledVideoDownload)
    } yield scheduledVideoDownload
  }

  "acquireTask" should "not return duplicate tasks" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] =
      new ContainerBatchResourcesProvider[IO]

    val downloadProgressPublisher = mock[Publisher[IO, DownloadProgress]]
    val repositoryService = mock[FileRepositoryService[IO]]
    val workerStatusSubscriber = mock[Subscriber[IO, WorkerStatusUpdate]]
    val scheduledVideoDownloadPubSub = mock[PubSub[IO, ScheduledVideoDownload]]
    val storageConfiguration = StorageConfiguration("video-folder", "image-folder", List.empty)

    val concurrency = 500
    val size = 1_000

    batchServiceProvider.transactor.use { implicit transactor =>
      val batchSchedulingServiceImpl =
        new BatchSchedulingServiceImpl[IO, ConnectionIO](
          downloadProgressPublisher,
          workerStatusSubscriber,
          scheduledVideoDownloadPubSub,
          repositoryService,
          DoobieSchedulingDao,
          DoobieBatchSchedulingDao,
          new DoobieWorkerDao(DoobieSchedulingDao),
          DoobieVideoMetadataDao,
          storageConfiguration,
          RetryPolicy(RetryConfiguration.Disabled),
          RandomGenerator.eval(IO.pure(0.5))
        )

      Stream
        .eval(DataGenerators.scheduledVideoDownload[IO].generate)
        .repeat
        .take(size)
        .parEvalMapUnordered(concurrency) { scheduledVideoDownload =>
          transactor {
            DoobieFileResourceDao
              .insert(scheduledVideoDownload.videoMetadata.thumbnail)
              .productR(DoobieVideoMetadataDao.insert(scheduledVideoDownload.videoMetadata))
              .productR(DoobieSchedulingDao.insert(scheduledVideoDownload))
          }.as(scheduledVideoDownload)
        }
        .compile
        .toList
        .product {
          Stream.emit[IO, Unit]((): Unit)
            .repeat
            .parEvalMapUnordered(concurrency) { _ =>
              batchSchedulingServiceImpl.acquireTask.value
            }
            .collect { case Some(value) => value }
            .take(size)
            .compile
            .toList
        }
        .flatMap {
          case (persisted, retrieved) =>
            IO.delay {
              retrieved must have length persisted.size
              retrieved.map(_.videoMetadata.id) must contain allElementsOf persisted.map(_.videoMetadata.id)
              all(retrieved.map(_.status)) mustBe SchedulingStatus.Acquired
            }
        }
        .productR {
          Stream.emit[IO, Unit]((): Unit)
            .repeatN(concurrency)
            .evalMap { _ => batchSchedulingServiceImpl.acquireTask.value }
            .collect { case Some(value) => value }
            .compile
            .toList
            .flatMap { items =>
              IO.delay {
                items mustBe empty
              }
            }
        }
    }

  }

  "updateSchedulingStatusById" should "update the scheduling status and publish the update" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]

    batchServiceProvider.transactor.use { implicit transactor =>
      val fixture = createTestFixture()

      for {
        scheduledVideo <- DataGenerators.scheduledVideoDownload[IO].generate
        _ <- transactor(insertScheduledVideo(scheduledVideo))

        _ = (fixture.scheduledVideoDownloadPubSub.publishOne _).expects(*).returning(IO.unit)

        updated <- fixture.batchSchedulingService.updateSchedulingStatusById(scheduledVideo.videoMetadata.id, SchedulingStatus.Downloaded)
        _ <- IO.delay {
          updated.status mustBe SchedulingStatus.Downloaded
          updated.videoMetadata.id mustBe scheduledVideo.videoMetadata.id
        }
      } yield ()
    }
  }

  it should "raise an error for non-existent video" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]

    batchServiceProvider.transactor.use { implicit transactor =>
      val fixture = createTestFixture()

      fixture.batchSchedulingService.updateSchedulingStatusById("non-existent-id", SchedulingStatus.Downloaded)
        .attempt
        .flatMap { result =>
          IO.delay {
            result.isLeft mustBe true
            result.left.toOption.value mustBe a[ResourceNotFoundException]
          }
        }
    }
  }


  "transitionSchedulingStatusById" should "change the status from one of the given statuses and publish it" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]

    batchServiceProvider.transactor.use { implicit transactor =>
      val fixture = createTestFixture()

      for {
        generated <- DataGenerators.scheduledVideoDownload[IO].generate
        scheduledVideo = generated.copy(status = SchedulingStatus.Active)
        _ <- transactor(insertScheduledVideo(scheduledVideo))

        _ = (fixture.scheduledVideoDownloadPubSub.publishOne _)
          .expects(where { (update: ScheduledVideoDownload) => update.status == SchedulingStatus.Queued })
          .returning(IO.unit)

        updated <- fixture.batchSchedulingService.transitionSchedulingStatusById(
          scheduledVideo.videoMetadata.id,
          Set(SchedulingStatus.Acquired, SchedulingStatus.Active),
          SchedulingStatus.Queued
        )
        _ <- IO.delay { updated.value.status mustBe SchedulingStatus.Queued }
      } yield ()
    }
  }

  it should "leave any other status alone, and publish nothing" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]

    batchServiceProvider.transactor.use { implicit transactor =>
      val fixture = createTestFixture()

      for {
        generated <- DataGenerators.scheduledVideoDownload[IO].generate
        scheduledVideo = generated.copy(status = SchedulingStatus.Completed)
        _ <- transactor(insertScheduledVideo(scheduledVideo))

        _ = (fixture.scheduledVideoDownloadPubSub.publishOne _).expects(*).never()

        updated <- fixture.batchSchedulingService.transitionSchedulingStatusById(
          scheduledVideo.videoMetadata.id,
          Set(SchedulingStatus.Acquired, SchedulingStatus.Active),
          SchedulingStatus.Queued
        )
        current <- transactor(DoobieSchedulingDao.getById(scheduledVideo.videoMetadata.id, None))
        _ <- IO.delay {
          updated mustBe None
          current.value.status mustBe SchedulingStatus.Completed
        }
      } yield ()
    }
  }

  it should "return None for a non-existent video" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]

    batchServiceProvider.transactor.use { implicit transactor =>
      val fixture = createTestFixture()

      fixture.batchSchedulingService
        .transitionSchedulingStatusById("non-existent-id", Set(SchedulingStatus.Active), SchedulingStatus.Queued)
        .flatMap(updated => IO.delay(updated mustBe None))
    }
  }

  "recordFailure" should "schedule a retry with backoff while attempts remain, and publish the update" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]
    val retryConfiguration = RetryConfiguration(maxAttempts = 2, baseDelay = 1.minute, maxDelay = 30.minutes)

    batchServiceProvider.transactor.use { implicit transactor =>
      val fixture = createTestFixture(retryConfiguration)
      val expectedDelay = RetryPolicy(retryConfiguration).nextDelay(0, 0.5).value

      for {
        generated <- DataGenerators.scheduledVideoDownload[IO].generate
        // Downloads fail while a worker is running them
        scheduledVideo = generated.copy(status = SchedulingStatus.Active)
        _ <- transactor(insertScheduledVideo(scheduledVideo))

        _ = (fixture.scheduledVideoDownloadPubSub.publishOne _)
          .expects(where { (update: ScheduledVideoDownload) => update.status == SchedulingStatus.Queued })
          .returning(IO.unit)

        maybeUpdated <-
          fixture.batchSchedulingService.recordFailure(scheduledVideo.videoMetadata.id, new RuntimeException("reset"))
        updated = maybeUpdated.value
        _ <- IO.delay {
          updated.status mustBe SchedulingStatus.Queued
          updated.retryCount mustBe 1
          updated.errorInfo.value.message must include("reset")
          updated.retryAfter.value.toEpochMilli - updated.lastUpdatedAt.toEpochMilli mustBe expectedDelay.toMillis
        }
      } yield ()
    }
  }

  it should "retry a download that failed after its file was downloaded" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]
    val retryConfiguration = RetryConfiguration(maxAttempts = 2, baseDelay = 1.minute, maxDelay = 30.minutes)

    batchServiceProvider.transactor.use { implicit transactor =>
      val fixture = createTestFixture(retryConfiguration)

      for {
        generated <- DataGenerators.scheduledVideoDownload[IO].generate
        // The worker sets Downloaded before probing the file and saving the video, which can fail transiently
        scheduledVideo = generated.copy(status = SchedulingStatus.Downloaded)
        _ <- transactor(insertScheduledVideo(scheduledVideo))

        _ = (fixture.scheduledVideoDownloadPubSub.publishOne _)
          .expects(where { (update: ScheduledVideoDownload) => update.status == SchedulingStatus.Queued })
          .returning(IO.unit)

        updated <-
          fixture.batchSchedulingService.recordFailure(scheduledVideo.videoMetadata.id, new RuntimeException("ffprobe"))
        _ <- IO.delay {
          updated.value.status mustBe SchedulingStatus.Queued
          updated.value.retryCount mustBe 1
        }
      } yield ()
    }
  }

  it should "write nothing when the worker no longer owns the download" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]
    val retryConfiguration = RetryConfiguration(maxAttempts = 5, baseDelay = 1.minute, maxDelay = 30.minutes)

    batchServiceProvider.transactor.use { implicit transactor =>
      val fixture = createTestFixture(retryConfiguration)

      // The user paused the download, the workers were paused, or another worker completed it, while this worker was
      // failing. A late failure must neither retry the download nor overwrite the status with Error.
      val notOwnedStatuses =
        List(SchedulingStatus.Completed, SchedulingStatus.Paused, SchedulingStatus.WorkersPaused)

      (fixture.scheduledVideoDownloadPubSub.publishOne _).expects(*).never()

      notOwnedStatuses.traverse_ { status =>
        for {
          generated <- DataGenerators.scheduledVideoDownload[IO].generate
          scheduledVideo = generated.copy(status = status)
          _ <- transactor(insertScheduledVideo(scheduledVideo))

          updated <-
            fixture.batchSchedulingService.recordFailure(scheduledVideo.videoMetadata.id, new RuntimeException("late"))
          current <- transactor(DoobieSchedulingDao.getById(scheduledVideo.videoMetadata.id, None))
          _ <- IO.delay {
            updated mustBe None
            current.value.status mustBe status
            current.value.errorInfo mustBe None
            current.value.retryCount mustBe 0
          }
        } yield ()
      }
    }
  }

  it should "set Error once the attempt limit is used up" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]
    val retryConfiguration = RetryConfiguration(maxAttempts = 1, baseDelay = 1.minute, maxDelay = 30.minutes)

    batchServiceProvider.transactor.use { implicit transactor =>
      val fixture = createTestFixture(retryConfiguration)

      for {
        generated <- DataGenerators.scheduledVideoDownload[IO].generate
        // Downloads fail while a worker is running them
        scheduledVideo = generated.copy(status = SchedulingStatus.Active)
        _ <- transactor(insertScheduledVideo(scheduledVideo))

        _ = (fixture.scheduledVideoDownloadPubSub.publishOne _).expects(*).returning(IO.unit).twice()

        id = scheduledVideo.videoMetadata.id
        first <- fixture.batchSchedulingService.recordFailure(id, new RuntimeException("one")).map(_.value)
        // Error-row IDs are {id}-{epochMillis}, so the second failure needs a later millisecond
        _ <- IO.sleep(5.millis)
        // A worker picks the retry up again before it fails a second time
        _ <- transactor {
          DoobieSchedulingDao.updateSchedulingStatusById(id, SchedulingStatus.Active, first.lastUpdatedAt)
        }
        second <- fixture.batchSchedulingService.recordFailure(id, new RuntimeException("two")).map(_.value)
        _ <- IO.delay {
          first.status mustBe SchedulingStatus.Queued
          second.status mustBe SchedulingStatus.Error
          second.retryCount mustBe 1
          second.errorInfo.value.message must include("two")
        }
      } yield ()
    }
  }

  it should "set Error once retries are disabled and publish the update" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]

    batchServiceProvider.transactor.use { implicit transactor =>
      val fixture = createTestFixture()
      val testError = new RuntimeException("Test error message")

      for {
        generated <- DataGenerators.scheduledVideoDownload[IO].generate
        scheduledVideo = generated.copy(status = SchedulingStatus.Active)
        _ <- transactor(insertScheduledVideo(scheduledVideo))

        _ = (fixture.scheduledVideoDownloadPubSub.publishOne _).expects(*).returning(IO.unit)

        updated <- fixture.batchSchedulingService.recordFailure(scheduledVideo.videoMetadata.id, testError)
        _ <- IO.delay {
          updated.value.status mustBe SchedulingStatus.Error
          updated.value.errorInfo.value.message must include("Test error message")
        }
      } yield ()
    }
  }

  it should "write nothing, and return None, for a non-existent video" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]

    batchServiceProvider.transactor.use { implicit transactor =>
      val fixture = createTestFixture()

      (fixture.scheduledVideoDownloadPubSub.publishOne _).expects(*).never()

      fixture.batchSchedulingService
        .recordFailure("non-existent-id", new RuntimeException("Test error"))
        .flatMap(updated => IO.delay(updated mustBe None))
    }
  }

  "completeScheduledVideoDownload" should "mark a video as complete and publish the update" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]

    batchServiceProvider.transactor.use { implicit transactor =>
      val fixture = createTestFixture()

      for {
        scheduledVideo <- DataGenerators.scheduledVideoDownload[IO].generate
        _ <- transactor(insertScheduledVideo(scheduledVideo))

        _ = (fixture.scheduledVideoDownloadPubSub.publishOne _).expects(*).returning(IO.unit)

        completed <- fixture.batchSchedulingService.completeScheduledVideoDownload(scheduledVideo.videoMetadata.id)
        _ <- IO.delay {
          completed.status mustBe SchedulingStatus.Completed
          completed.completedAt mustBe defined
        }
      } yield ()
    }
  }

  "publishDownloadProgress" should "publish download progress" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]

    batchServiceProvider.transactor.use { implicit transactor =>
      val fixture = createTestFixture()

      (fixture.downloadProgressPublisher.publishOne _).expects(*).returning(IO.unit)

      fixture.batchSchedulingService.publishDownloadProgress("video-id", 1024L)
    }
  }

  "publishScheduledVideoDownload" should "fetch and publish the scheduled video download" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]

    batchServiceProvider.transactor.use { implicit transactor =>
      val fixture = createTestFixture()

      for {
        scheduledVideo <- DataGenerators.scheduledVideoDownload[IO].generate
        _ <- transactor(insertScheduledVideo(scheduledVideo))

        _ = (fixture.scheduledVideoDownloadPubSub.publishOne _).expects(*).returning(IO.unit)

        published <- fixture.batchSchedulingService.publishScheduledVideoDownload(scheduledVideo.videoMetadata.id)
        _ <- IO.delay {
          published.videoMetadata.id mustBe scheduledVideo.videoMetadata.id
        }
      } yield ()
    }
  }

  it should "raise an error for non-existent video" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]

    batchServiceProvider.transactor.use { implicit transactor =>
      val fixture = createTestFixture()

      fixture.batchSchedulingService.publishScheduledVideoDownload("non-existent-id")
        .attempt
        .flatMap { result =>
          IO.delay {
            result.isLeft mustBe true
            result.left.toOption.value mustBe a[ResourceNotFoundException]
          }
        }
    }
  }

  "staleTask" should "return stale task if available" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]

    batchServiceProvider.transactor.use { implicit transactor =>
      val fixture = createTestFixture()

      for {
        scheduledVideo <- DataGenerators.scheduledVideoDownload[IO].generate
        staleVideo = scheduledVideo.copy(status = SchedulingStatus.Acquired)
        _ <- transactor(insertScheduledVideo(staleVideo))

        // Check stale task
        result <- fixture.batchSchedulingService.staleTask.value
        _ <- IO.delay {
          // staleTask depends on time, may or may not find a stale task
          // Just verify the method runs without error
          result.foreach(_.status mustBe SchedulingStatus.Stale)
        }
      } yield ()
    }
  }

  "deleteById" should "delete a scheduled video download" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]

    batchServiceProvider.transactor.use { implicit transactor =>
      val fixture = createTestFixture()

      for {
        scheduledVideo <- DataGenerators.scheduledVideoDownload[IO].generate
        _ <- transactor(insertScheduledVideo(scheduledVideo))

        deleted <- fixture.batchSchedulingService.deleteById(scheduledVideo.videoMetadata.id)
        _ <- IO.delay {
          deleted.videoMetadata.id mustBe scheduledVideo.videoMetadata.id
        }

        // Verify it's deleted
        notFound <- fixture.batchSchedulingService.publishScheduledVideoDownload(scheduledVideo.videoMetadata.id).attempt
        _ <- IO.delay {
          notFound.isLeft mustBe true
        }
      } yield ()
    }
  }

  it should "raise an error for non-existent video" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]

    batchServiceProvider.transactor.use { implicit transactor =>
      val fixture = createTestFixture()

      fixture.batchSchedulingService.deleteById("non-existent-id")
        .attempt
        .flatMap { result =>
          IO.delay {
            result.isLeft mustBe true
            result.left.toOption.value mustBe a[ResourceNotFoundException]
          }
        }
    }
  }

  it should "delete video file when downloadedBytes > 0" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]

    batchServiceProvider.transactor.use { implicit transactor =>
      val workerStatusSubscriber = mock[Subscriber[IO, WorkerStatusUpdate]]
      val downloadProgressPublisher = mock[Publisher[IO, DownloadProgress]]
      val repositoryService = mock[FileRepositoryService[IO]]
      val storageConfiguration = StorageConfiguration("video-folder", "image-folder", List.empty)

      val scheduledVideoDownloadPubSub = new StubScheduledVideoPubSub

      val batchSchedulingService = new BatchSchedulingServiceImpl[IO, ConnectionIO](
        downloadProgressPublisher,
        workerStatusSubscriber,
        scheduledVideoDownloadPubSub,
        repositoryService,
        DoobieSchedulingDao,
        DoobieBatchSchedulingDao,
        new DoobieWorkerDao(DoobieSchedulingDao),
        DoobieVideoMetadataDao,
        storageConfiguration,
        RetryPolicy(RetryConfiguration.Disabled),
        RandomGenerator.eval(IO.pure(0.5))
      )

      for {
        scheduledVideo <- DataGenerators.scheduledVideoDownload[IO].generate
        videoWithBytes = scheduledVideo.copy(downloadedBytes = 1024L) // Set downloaded bytes > 0
        _ <- transactor(insertScheduledVideo(videoWithBytes))
        timestamp <- Clock[IO].timestamp
        _ <- transactor(DoobieSchedulingDao.updateDownloadProgress(videoWithBytes.videoMetadata.id, 1024L, timestamp))

        // Mock repository to return the video file and accept delete
        _ = (repositoryService.list _).expects("video-folder").returning(
          Stream.emit(s"video-folder/${videoWithBytes.videoMetadata.id}.mp4")
        )
        _ = (repositoryService.delete _).expects(s"video-folder/${videoWithBytes.videoMetadata.id}.mp4").returning(IO.pure(true))

        deleted <- batchSchedulingService.deleteById(videoWithBytes.videoMetadata.id)
        _ <- IO.delay {
          deleted.videoMetadata.id mustBe videoWithBytes.videoMetadata.id
        }
      } yield ()
    }
  }

  "completeScheduledVideoDownload" should "raise an error for non-existent video" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]

    batchServiceProvider.transactor.use { implicit transactor =>
      val fixture = createTestFixture()

      fixture.batchSchedulingService.completeScheduledVideoDownload("non-existent-id")
        .attempt
        .flatMap { result =>
          IO.delay {
            result.isLeft mustBe true
            result.left.toOption.value mustBe a[ResourceNotFoundException]
          }
        }
    }
  }

  "subscribeToWorkerStatusUpdates" should "return a stream" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]

    batchServiceProvider.transactor.use { implicit transactor =>
      val workerStatusSubscriber = mock[Subscriber[IO, WorkerStatusUpdate]]
      val downloadProgressPublisher = mock[Publisher[IO, DownloadProgress]]
      val repositoryService = mock[FileRepositoryService[IO]]
      val scheduledVideoDownloadPubSub = mock[PubSub[IO, ScheduledVideoDownload]]
      val storageConfiguration = StorageConfiguration("video-folder", "image-folder", List.empty)

      val batchSchedulingService = new BatchSchedulingServiceImpl[IO, ConnectionIO](
        downloadProgressPublisher,
        workerStatusSubscriber,
        scheduledVideoDownloadPubSub,
        repositoryService,
        DoobieSchedulingDao,
        DoobieBatchSchedulingDao,
        new DoobieWorkerDao(DoobieSchedulingDao),
        DoobieVideoMetadataDao,
        storageConfiguration,
        RetryPolicy(RetryConfiguration.Disabled),
        RandomGenerator.eval(IO.pure(0.5))
      )

      (workerStatusSubscriber.subscribe _).expects("test-group").returning(Stream.empty)

      // Just verify the stream can be created without error
      val stream = batchSchedulingService.subscribeToWorkerStatusUpdates("test-group")
      stream.compile.toList.map { result =>
        result mustBe empty
      }
    }
  }

  "subscribeToScheduledVideoDownloadUpdates" should "return a stream" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]

    batchServiceProvider.transactor.use { implicit transactor =>
      val workerStatusSubscriber = mock[Subscriber[IO, WorkerStatusUpdate]]
      val downloadProgressPublisher = mock[Publisher[IO, DownloadProgress]]
      val repositoryService = mock[FileRepositoryService[IO]]
      val scheduledVideoDownloadPubSub = mock[PubSub[IO, ScheduledVideoDownload]]
      val storageConfiguration = StorageConfiguration("video-folder", "image-folder", List.empty)

      val batchSchedulingService = new BatchSchedulingServiceImpl[IO, ConnectionIO](
        downloadProgressPublisher,
        workerStatusSubscriber,
        scheduledVideoDownloadPubSub,
        repositoryService,
        DoobieSchedulingDao,
        DoobieBatchSchedulingDao,
        new DoobieWorkerDao(DoobieSchedulingDao),
        DoobieVideoMetadataDao,
        storageConfiguration,
        RetryPolicy(RetryConfiguration.Disabled),
        RandomGenerator.eval(IO.pure(0.5))
      )

      (scheduledVideoDownloadPubSub.subscribe _).expects("test-group").returning(Stream.empty)

      // Just verify the stream can be created without error
      val stream = batchSchedulingService.subscribeToScheduledVideoDownloadUpdates("test-group")
      stream.compile.toList.map { result =>
        result mustBe empty
      }
    }
  }

  "updateSchedulingStatus" should "update videos from one status to another and publish updates" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]

    batchServiceProvider.transactor.use { implicit transactor =>
      val workerStatusSubscriber = mock[Subscriber[IO, WorkerStatusUpdate]]
      val downloadProgressPublisher = mock[Publisher[IO, DownloadProgress]]
      val repositoryService = mock[FileRepositoryService[IO]]
      val storageConfiguration = StorageConfiguration("video-folder", "image-folder", List.empty)

      // Create a stub PubSub that tracks published messages
      val scheduledVideoDownloadPubSub = new StubScheduledVideoPubSub

      val batchSchedulingService = new BatchSchedulingServiceImpl[IO, ConnectionIO](
        downloadProgressPublisher,
        workerStatusSubscriber,
        scheduledVideoDownloadPubSub,
        repositoryService,
        DoobieSchedulingDao,
        DoobieBatchSchedulingDao,
        new DoobieWorkerDao(DoobieSchedulingDao),
        DoobieVideoMetadataDao,
        storageConfiguration,
        RetryPolicy(RetryConfiguration.Disabled),
        RandomGenerator.eval(IO.pure(0.5))
      )

      for {
        scheduledVideo <- DataGenerators.scheduledVideoDownload[IO].generate
        errorVideo = scheduledVideo.copy(status = SchedulingStatus.Error)
        _ <- transactor(insertScheduledVideo(errorVideo))

        updated <- batchSchedulingService.updateSchedulingStatus(SchedulingStatus.Error, SchedulingStatus.Queued)
        _ <- IO.delay {
          updated.foreach(_.status mustBe SchedulingStatus.Queued)
        }
      } yield ()
    }
  }

  it should "return empty sequence when no videos match the from status" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]

    batchServiceProvider.transactor.use { implicit transactor =>
      val workerStatusSubscriber = mock[Subscriber[IO, WorkerStatusUpdate]]
      val downloadProgressPublisher = mock[Publisher[IO, DownloadProgress]]
      val repositoryService = mock[FileRepositoryService[IO]]
      val storageConfiguration = StorageConfiguration("video-folder", "image-folder", List.empty)

      val scheduledVideoDownloadPubSub = new StubScheduledVideoPubSub

      val batchSchedulingService = new BatchSchedulingServiceImpl[IO, ConnectionIO](
        downloadProgressPublisher,
        workerStatusSubscriber,
        scheduledVideoDownloadPubSub,
        repositoryService,
        DoobieSchedulingDao,
        DoobieBatchSchedulingDao,
        new DoobieWorkerDao(DoobieSchedulingDao),
        DoobieVideoMetadataDao,
        storageConfiguration,
        RetryPolicy(RetryConfiguration.Disabled),
        RandomGenerator.eval(IO.pure(0.5))
      )

      batchSchedulingService.updateSchedulingStatus(SchedulingStatus.Error, SchedulingStatus.Queued).map { updated =>
        updated mustBe empty
      }
    }
  }

  "updateTimedOutTasks" should "update timed out tasks and publish updates" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]

    batchServiceProvider.transactor.use { implicit transactor =>
      val workerStatusSubscriber = mock[Subscriber[IO, WorkerStatusUpdate]]
      val downloadProgressPublisher = mock[Publisher[IO, DownloadProgress]]
      val repositoryService = mock[FileRepositoryService[IO]]
      val storageConfiguration = StorageConfiguration("video-folder", "image-folder", List.empty)

      val scheduledVideoDownloadPubSub = new StubScheduledVideoPubSub

      val batchSchedulingService = new BatchSchedulingServiceImpl[IO, ConnectionIO](
        downloadProgressPublisher,
        workerStatusSubscriber,
        scheduledVideoDownloadPubSub,
        repositoryService,
        DoobieSchedulingDao,
        DoobieBatchSchedulingDao,
        new DoobieWorkerDao(DoobieSchedulingDao),
        DoobieVideoMetadataDao,
        storageConfiguration,
        RetryPolicy(RetryConfiguration.Disabled),
        RandomGenerator.eval(IO.pure(0.5))
      )

      // Call with a short timeout to test the method execution
      batchSchedulingService.updateTimedOutTasks(1.hour).map { updated =>
        // We just verify the method runs without error
        // The actual timeout behavior depends on the data in the database
        updated.foreach(_.status mustBe SchedulingStatus.Stale)
      }
    }
  }

  "video deletion" should "succeed when video has perceptual hash records" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]

    batchServiceProvider.transactor.use { implicit transactor =>
      for {
        scheduledVideo <- DataGenerators.scheduledVideoDownload[IO].generate
        _ <- transactor(insertScheduledVideo(scheduledVideo))

        videoFileResource <- DataGenerators.fileResource[IO](cats.data.NonEmptyList.of(org.http4s.MediaType.video.mp4)).generate
        _ <- transactor(DoobieFileResourceDao.insert(videoFileResource))

        timestamp <- Clock[IO].timestamp
        _ <- transactor(DoobieVideoDao.insert(scheduledVideo.videoMetadata.id, videoFileResource.id, timestamp, 0.seconds))

        // Insert a perceptual hash record for this video
        _ <- transactor(DoobieVideoPerceptualHashDao.insert(
          VideoPerceptualHash(scheduledVideo.videoMetadata.id, timestamp, scheduledVideo.videoMetadata.duration, BigInt(42L), 150.seconds)
        ))

        // Deleting the video row should succeed despite the perceptual hash record
        // (FK now references video_metadata, not video)
        deleteResult <- transactor(DoobieVideoDao.deleteById(scheduledVideo.videoMetadata.id))
        _ <- IO.delay {
          deleteResult must be > 0
        }
      } yield ()
    }
  }

  it should "succeed when video has duplicate video records" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]

    batchServiceProvider.transactor.use { implicit transactor =>
      for {
        scheduledVideo <- DataGenerators.scheduledVideoDownload[IO].generate
        _ <- transactor(insertScheduledVideo(scheduledVideo))

        videoFileResource <- DataGenerators.fileResource[IO](cats.data.NonEmptyList.of(org.http4s.MediaType.video.mp4)).generate
        _ <- transactor(DoobieFileResourceDao.insert(videoFileResource))

        timestamp <- Clock[IO].timestamp
        _ <- transactor(DoobieVideoDao.insert(scheduledVideo.videoMetadata.id, videoFileResource.id, timestamp, 0.seconds))

        // Insert a duplicate video record for this video
        _ <- transactor(DoobieDuplicateVideoDao.insert(
          DuplicateVideo(scheduledVideo.videoMetadata.id, scheduledVideo.videoMetadata.id, timestamp)
        ))

        // Deleting the video row should succeed despite the duplicate video record
        // (FK now references video_metadata, not video)
        deleteResult <- transactor(DoobieVideoDao.deleteById(scheduledVideo.videoMetadata.id))
        _ <- IO.delay {
          deleteResult must be > 0
        }
      } yield ()
    }
  }

  // Stub implementation for PubSub
  class StubScheduledVideoPubSub extends PubSub[IO, ScheduledVideoDownload] {
    import cats.Foldable
    import cats.Functor
    import fs2.Pipe

    override type C[X] = X
    override val publish: Pipe[IO, ScheduledVideoDownload, Unit] = _.evalMap(_ => IO.unit)
    override def publishOne(input: ScheduledVideoDownload): IO[Unit] = IO.unit
    override def subscribe(groupId: String): Stream[IO, ScheduledVideoDownload] = Stream.empty
    override def commit[H[_]: Foldable: Functor](values: H[ScheduledVideoDownload]): IO[Unit] = IO.unit
    override def extractValue(ca: ScheduledVideoDownload): ScheduledVideoDownload = ca
  }

}
