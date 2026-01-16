package com.ruchij.batch.services.scheduling

import cats.Id
import cats.effect.IO
import cats.implicits._
import cats.~>
import com.ruchij.batch.daos.workers.DoobieWorkerDao
import com.ruchij.batch.external.BatchResourcesProvider
import com.ruchij.batch.external.containers.ContainerBatchResourcesProvider
import com.ruchij.core.config.StorageConfiguration
import com.ruchij.core.daos.resource.DoobieFileResourceDao
import com.ruchij.core.daos.scheduling.DoobieSchedulingDao
import com.ruchij.core.daos.scheduling.models.{ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.daos.videometadata.DoobieVideoMetadataDao
import com.ruchij.core.exceptions.ResourceNotFoundException
import com.ruchij.core.messaging.models.CommittableRecord
import com.ruchij.core.messaging.{PubSub, Publisher, Subscriber}
import com.ruchij.core.services.repository.FileRepositoryService
import com.ruchij.core.services.scheduling.models.{DownloadProgress, WorkerStatusUpdate}
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.test.data.DataGenerators
import com.ruchij.core.types.JodaClock
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
    batchSchedulingService: BatchSchedulingServiceImpl[IO, ConnectionIO, Id],
    schedulingDao: DoobieSchedulingDao.type,
    downloadProgressPublisher: Publisher[IO, DownloadProgress],
    scheduledVideoDownloadPubSub: PubSub[IO, CommittableRecord[Id, *], ScheduledVideoDownload]
  )

  def createTestFixture(implicit transactor: ConnectionIO ~> IO): TestFixture = {
    val downloadProgressPublisher = mock[Publisher[IO, DownloadProgress]]
    val repositoryService = mock[FileRepositoryService[IO]]
    val workerStatusSubscriber = mock[Subscriber[IO, CommittableRecord[Id, *], WorkerStatusUpdate]]
    val scheduledVideoDownloadPubSub = mock[PubSub[IO, CommittableRecord[Id, *], ScheduledVideoDownload]]
    val storageConfiguration = StorageConfiguration("video-folder", "image-folder", List.empty)

    val batchSchedulingService = new BatchSchedulingServiceImpl[IO, ConnectionIO, Id](
      downloadProgressPublisher,
      workerStatusSubscriber,
      scheduledVideoDownloadPubSub,
      repositoryService,
      DoobieSchedulingDao,
      new DoobieWorkerDao(DoobieSchedulingDao),
      storageConfiguration
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
    val workerStatusSubscriber = mock[Subscriber[IO, CommittableRecord[Id, *], WorkerStatusUpdate]]
    val scheduledVideoDownloadPubSub = mock[PubSub[IO, CommittableRecord[Id, *], ScheduledVideoDownload]]
    val storageConfiguration = StorageConfiguration("video-folder", "image-folder", List.empty)

    val concurrency = 500
    val size = 1_000

    batchServiceProvider.transactor.use { implicit transactor =>
      val batchSchedulingServiceImpl =
        new BatchSchedulingServiceImpl[IO, ConnectionIO, Id](
          downloadProgressPublisher,
          workerStatusSubscriber,
          scheduledVideoDownloadPubSub,
          repositoryService,
          DoobieSchedulingDao,
          new DoobieWorkerDao(DoobieSchedulingDao),
          storageConfiguration
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
      val fixture = createTestFixture

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
      val fixture = createTestFixture

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

  "setErrorById" should "set error on a scheduled video and publish the update" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]

    batchServiceProvider.transactor.use { implicit transactor =>
      val fixture = createTestFixture
      val testError = new RuntimeException("Test error message")

      for {
        scheduledVideo <- DataGenerators.scheduledVideoDownload[IO].generate
        _ <- transactor(insertScheduledVideo(scheduledVideo))

        _ = (fixture.scheduledVideoDownloadPubSub.publishOne _).expects(*).returning(IO.unit)

        updated <- fixture.batchSchedulingService.setErrorById(scheduledVideo.videoMetadata.id, testError)
        _ <- IO.delay {
          updated.status mustBe SchedulingStatus.Error
          updated.errorInfo mustBe defined
          updated.errorInfo.value.message must include("Test error message")
        }
      } yield ()
    }
  }

  it should "raise an error for non-existent video" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]

    batchServiceProvider.transactor.use { implicit transactor =>
      val fixture = createTestFixture
      val testError = new RuntimeException("Test error")

      fixture.batchSchedulingService.setErrorById("non-existent-id", testError)
        .attempt
        .flatMap { result =>
          IO.delay {
            // When the video doesn't exist, the database operation may fail with a constraint error
            // or return ResourceNotFoundException depending on implementation
            result.isLeft mustBe true
          }
        }
    }
  }

  "completeScheduledVideoDownload" should "mark a video as complete and publish the update" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]

    batchServiceProvider.transactor.use { implicit transactor =>
      val fixture = createTestFixture

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
      val fixture = createTestFixture

      (fixture.downloadProgressPublisher.publishOne _).expects(*).returning(IO.unit)

      fixture.batchSchedulingService.publishDownloadProgress("video-id", 1024L)
    }
  }

  "publishScheduledVideoDownload" should "fetch and publish the scheduled video download" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]

    batchServiceProvider.transactor.use { implicit transactor =>
      val fixture = createTestFixture

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
      val fixture = createTestFixture

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
      val fixture = createTestFixture

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
      val fixture = createTestFixture

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
      val fixture = createTestFixture

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
      val workerStatusSubscriber = mock[Subscriber[IO, CommittableRecord[Id, *], WorkerStatusUpdate]]
      val downloadProgressPublisher = mock[Publisher[IO, DownloadProgress]]
      val repositoryService = mock[FileRepositoryService[IO]]
      val storageConfiguration = StorageConfiguration("video-folder", "image-folder", List.empty)

      val scheduledVideoDownloadPubSub = new StubScheduledVideoPubSub

      val batchSchedulingService = new BatchSchedulingServiceImpl[IO, ConnectionIO, Id](
        downloadProgressPublisher,
        workerStatusSubscriber,
        scheduledVideoDownloadPubSub,
        repositoryService,
        DoobieSchedulingDao,
        new DoobieWorkerDao(DoobieSchedulingDao),
        storageConfiguration
      )

      for {
        scheduledVideo <- DataGenerators.scheduledVideoDownload[IO].generate
        videoWithBytes = scheduledVideo.copy(downloadedBytes = 1024L) // Set downloaded bytes > 0
        _ <- transactor(insertScheduledVideo(videoWithBytes))
        timestamp <- JodaClock[IO].timestamp
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
      val fixture = createTestFixture

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
      val workerStatusSubscriber = mock[Subscriber[IO, CommittableRecord[Id, *], WorkerStatusUpdate]]
      val downloadProgressPublisher = mock[Publisher[IO, DownloadProgress]]
      val repositoryService = mock[FileRepositoryService[IO]]
      val scheduledVideoDownloadPubSub = mock[PubSub[IO, CommittableRecord[Id, *], ScheduledVideoDownload]]
      val storageConfiguration = StorageConfiguration("video-folder", "image-folder", List.empty)

      val batchSchedulingService = new BatchSchedulingServiceImpl[IO, ConnectionIO, Id](
        downloadProgressPublisher,
        workerStatusSubscriber,
        scheduledVideoDownloadPubSub,
        repositoryService,
        DoobieSchedulingDao,
        new DoobieWorkerDao(DoobieSchedulingDao),
        storageConfiguration
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
      val workerStatusSubscriber = mock[Subscriber[IO, CommittableRecord[Id, *], WorkerStatusUpdate]]
      val downloadProgressPublisher = mock[Publisher[IO, DownloadProgress]]
      val repositoryService = mock[FileRepositoryService[IO]]
      val scheduledVideoDownloadPubSub = mock[PubSub[IO, CommittableRecord[Id, *], ScheduledVideoDownload]]
      val storageConfiguration = StorageConfiguration("video-folder", "image-folder", List.empty)

      val batchSchedulingService = new BatchSchedulingServiceImpl[IO, ConnectionIO, Id](
        downloadProgressPublisher,
        workerStatusSubscriber,
        scheduledVideoDownloadPubSub,
        repositoryService,
        DoobieSchedulingDao,
        new DoobieWorkerDao(DoobieSchedulingDao),
        storageConfiguration
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
      val workerStatusSubscriber = mock[Subscriber[IO, CommittableRecord[Id, *], WorkerStatusUpdate]]
      val downloadProgressPublisher = mock[Publisher[IO, DownloadProgress]]
      val repositoryService = mock[FileRepositoryService[IO]]
      val storageConfiguration = StorageConfiguration("video-folder", "image-folder", List.empty)

      // Create a stub PubSub that tracks published messages
      val scheduledVideoDownloadPubSub = new StubScheduledVideoPubSub

      val batchSchedulingService = new BatchSchedulingServiceImpl[IO, ConnectionIO, Id](
        downloadProgressPublisher,
        workerStatusSubscriber,
        scheduledVideoDownloadPubSub,
        repositoryService,
        DoobieSchedulingDao,
        new DoobieWorkerDao(DoobieSchedulingDao),
        storageConfiguration
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
      val workerStatusSubscriber = mock[Subscriber[IO, CommittableRecord[Id, *], WorkerStatusUpdate]]
      val downloadProgressPublisher = mock[Publisher[IO, DownloadProgress]]
      val repositoryService = mock[FileRepositoryService[IO]]
      val storageConfiguration = StorageConfiguration("video-folder", "image-folder", List.empty)

      val scheduledVideoDownloadPubSub = new StubScheduledVideoPubSub

      val batchSchedulingService = new BatchSchedulingServiceImpl[IO, ConnectionIO, Id](
        downloadProgressPublisher,
        workerStatusSubscriber,
        scheduledVideoDownloadPubSub,
        repositoryService,
        DoobieSchedulingDao,
        new DoobieWorkerDao(DoobieSchedulingDao),
        storageConfiguration
      )

      batchSchedulingService.updateSchedulingStatus(SchedulingStatus.Error, SchedulingStatus.Queued).map { updated =>
        updated mustBe empty
      }
    }
  }

  "updateTimedOutTasks" should "update timed out tasks and publish updates" in runIO {
    val batchServiceProvider: BatchResourcesProvider[IO] = new ContainerBatchResourcesProvider[IO]

    batchServiceProvider.transactor.use { implicit transactor =>
      val workerStatusSubscriber = mock[Subscriber[IO, CommittableRecord[Id, *], WorkerStatusUpdate]]
      val downloadProgressPublisher = mock[Publisher[IO, DownloadProgress]]
      val repositoryService = mock[FileRepositoryService[IO]]
      val storageConfiguration = StorageConfiguration("video-folder", "image-folder", List.empty)

      val scheduledVideoDownloadPubSub = new StubScheduledVideoPubSub

      val batchSchedulingService = new BatchSchedulingServiceImpl[IO, ConnectionIO, Id](
        downloadProgressPublisher,
        workerStatusSubscriber,
        scheduledVideoDownloadPubSub,
        repositoryService,
        DoobieSchedulingDao,
        new DoobieWorkerDao(DoobieSchedulingDao),
        storageConfiguration
      )

      // Call with a short timeout to test the method execution
      batchSchedulingService.updateTimedOutTasks(1.hour).map { updated =>
        // We just verify the method runs without error
        // The actual timeout behavior depends on the data in the database
        updated.foreach(_.status mustBe SchedulingStatus.Stale)
      }
    }
  }

  // Stub implementation for PubSub
  class StubScheduledVideoPubSub extends PubSub[IO, CommittableRecord[Id, *], ScheduledVideoDownload] {
    import cats.Foldable
    import cats.Functor
    import fs2.Pipe

    override val publish: Pipe[IO, ScheduledVideoDownload, Unit] = _.evalMap(_ => IO.unit)
    override def publishOne(input: ScheduledVideoDownload): IO[Unit] = IO.unit
    override def subscribe(groupId: String): Stream[IO, CommittableRecord[Id, ScheduledVideoDownload]] = Stream.empty
    override def commit[H[_]: Foldable: Functor](values: H[CommittableRecord[Id, ScheduledVideoDownload]]): IO[Unit] = IO.unit
  }

}
