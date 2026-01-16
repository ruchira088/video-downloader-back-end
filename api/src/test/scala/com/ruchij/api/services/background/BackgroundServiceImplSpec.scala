package com.ruchij.api.services.background

import cats.{Foldable, Functor, Id}
import cats.effect.IO
import com.ruchij.api.services.health.models.messaging.HealthCheckMessage
import com.ruchij.api.services.scheduling.ApiSchedulingService
import com.ruchij.api.services.scheduling.models.ScheduledVideoResult
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.scheduling.models.{RangeValue, ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.daos.videometadata.models.{VideoMetadata, VideoSite}
import com.ruchij.core.daos.workers.models.WorkerStatus
import com.ruchij.core.messaging.Subscriber
import com.ruchij.core.messaging.models.CommittableRecord
import com.ruchij.core.services.scheduling.models.DownloadProgress
import com.ruchij.core.test.IOSupport.runIO
import fs2.Stream
import fs2.concurrent.Topic
import org.http4s.MediaType
import org.http4s.implicits.http4sLiteralsSyntax
import org.joda.time.DateTime
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.duration._

class BackgroundServiceImplSpec extends AnyFlatSpec with Matchers {

  private val timestamp = new DateTime(2024, 5, 15, 10, 30)

  private val sampleThumbnail =
    FileResource("thumbnail-1", timestamp, "/thumbnails/thumb1.jpg", MediaType.image.jpeg, 50000L)

  private val sampleVideoMetadata = VideoMetadata(
    uri"https://example.com/video1",
    "video-1",
    VideoSite.YTDownloaderSite("youtube"),
    "Sample Video",
    10.minutes,
    1024 * 1024 * 100L,
    sampleThumbnail
  )

  private val sampleScheduledVideoDownload = ScheduledVideoDownload(
    timestamp,
    timestamp,
    SchedulingStatus.Queued,
    0L,
    sampleVideoMetadata,
    None,
    None
  )

  class StubApiSchedulingService extends ApiSchedulingService[IO] {
    var updatedProgress: List[(String, DateTime, Long)] = List.empty

    override def updateDownloadProgress(id: String, timestamp: DateTime, downloadedBytes: Long): IO[ScheduledVideoDownload] = {
      updatedProgress = updatedProgress :+ (id, timestamp, downloadedBytes)
      IO.pure(sampleScheduledVideoDownload.copy(downloadedBytes = downloadedBytes))
    }

    override def schedule(uri: org.http4s.Uri, userId: String): IO[ScheduledVideoResult] =
      IO.pure(ScheduledVideoResult.AlreadyScheduled(sampleScheduledVideoDownload))

    override def search(
      term: Option[String],
      videoUrls: Option[cats.data.NonEmptyList[org.http4s.Uri]],
      durationRange: RangeValue[FiniteDuration],
      sizeRange: RangeValue[Long],
      pageNumber: Int,
      pageSize: Int,
      sortBy: com.ruchij.core.services.models.SortBy,
      order: com.ruchij.core.services.models.Order,
      schedulingStatuses: Option[cats.data.NonEmptyList[SchedulingStatus]],
      videoSites: Option[cats.data.NonEmptyList[VideoSite]],
      maybeUserId: Option[String]
    ): IO[Seq[ScheduledVideoDownload]] = IO.pure(Seq.empty)

    override def getById(id: String, maybeUserId: Option[String]): IO[ScheduledVideoDownload] =
      IO.pure(sampleScheduledVideoDownload)

    override def retryFailed(maybeUserId: Option[String]): IO[Seq[ScheduledVideoDownload]] =
      IO.pure(Seq.empty)

    override def updateSchedulingStatus(id: String, status: SchedulingStatus): IO[ScheduledVideoDownload] =
      IO.pure(sampleScheduledVideoDownload.copy(status = status))

    override def updateWorkerStatus(workerStatus: WorkerStatus): IO[Unit] = IO.unit

    override val getWorkerStatus: IO[WorkerStatus] = IO.pure(WorkerStatus.Available)

    override def deleteById(id: String, maybeUserId: Option[String]): IO[ScheduledVideoDownload] =
      IO.pure(sampleScheduledVideoDownload)
  }

  class StubSubscriber[A] extends Subscriber[IO, CommittableRecord[Id, *], A] {
    var subscribed = false
    var committed: List[CommittableRecord[Id, A]] = List.empty

    override def subscribe(groupId: String): Stream[IO, CommittableRecord[Id, A]] = {
      subscribed = true
      Stream.empty
    }

    override def commit[H[_]: Foldable: Functor](values: H[CommittableRecord[Id, A]]): IO[Unit] = IO.unit
  }

  "BackgroundServiceImpl.create" should "create a BackgroundServiceImpl with topics" in runIO {
    val apiSchedulingService = new StubApiSchedulingService()
    val downloadProgressSubscriber = new StubSubscriber[DownloadProgress]
    val healthCheckSubscriber = new StubSubscriber[HealthCheckMessage]
    val scheduledVideoDownloadsSubscriber = new StubSubscriber[ScheduledVideoDownload]

    BackgroundServiceImpl.create[IO, Id](
      apiSchedulingService,
      downloadProgressSubscriber,
      healthCheckSubscriber,
      scheduledVideoDownloadsSubscriber,
      "test-group"
    ).map { service =>
      service must not be null
    }
  }

  "downloadProgress" should "create a stream from the topic" in runIO {
    for {
      downloadProgressTopic <- Topic[IO, DownloadProgress]
      healthCheckTopic <- Topic[IO, HealthCheckMessage]
      scheduleVideoDownloadsTopic <- Topic[IO, ScheduledVideoDownload]

      service = new BackgroundServiceImpl[IO, Id](
        new StubApiSchedulingService(),
        new StubSubscriber[DownloadProgress],
        downloadProgressTopic,
        new StubSubscriber[HealthCheckMessage],
        healthCheckTopic,
        new StubSubscriber[ScheduledVideoDownload],
        scheduleVideoDownloadsTopic,
        "test-group"
      )

      progress = DownloadProgress("video-1", timestamp, 1024L)

      // Publish and receive in parallel
      received <- service.downloadProgress.take(1).compile.toList
        .timeout(1.second)
        .start
        .flatMap { fiber =>
          IO.sleep(50.millis) *>
            downloadProgressTopic.publish1(progress) *>
            fiber.joinWithNever
        }

      _ <- IO.delay {
        received must have length 1
        received.head mustBe progress
      }
    } yield ()
  }

  "healthChecks" should "create a stream from the topic" in runIO {
    for {
      downloadProgressTopic <- Topic[IO, DownloadProgress]
      healthCheckTopic <- Topic[IO, HealthCheckMessage]
      scheduleVideoDownloadsTopic <- Topic[IO, ScheduledVideoDownload]

      service = new BackgroundServiceImpl[IO, Id](
        new StubApiSchedulingService(),
        new StubSubscriber[DownloadProgress],
        downloadProgressTopic,
        new StubSubscriber[HealthCheckMessage],
        healthCheckTopic,
        new StubSubscriber[ScheduledVideoDownload],
        scheduleVideoDownloadsTopic,
        "test-group"
      )

      healthCheck = HealthCheckMessage("instance-1", timestamp)

      received <- service.healthChecks.take(1).compile.toList
        .timeout(1.second)
        .start
        .flatMap { fiber =>
          IO.sleep(50.millis) *>
            healthCheckTopic.publish1(healthCheck) *>
            fiber.joinWithNever
        }

      _ <- IO.delay {
        received must have length 1
        received.head mustBe healthCheck
      }
    } yield ()
  }

  "updates" should "create a stream from the topic" in runIO {
    for {
      downloadProgressTopic <- Topic[IO, DownloadProgress]
      healthCheckTopic <- Topic[IO, HealthCheckMessage]
      scheduleVideoDownloadsTopic <- Topic[IO, ScheduledVideoDownload]

      service = new BackgroundServiceImpl[IO, Id](
        new StubApiSchedulingService(),
        new StubSubscriber[DownloadProgress],
        downloadProgressTopic,
        new StubSubscriber[HealthCheckMessage],
        healthCheckTopic,
        new StubSubscriber[ScheduledVideoDownload],
        scheduleVideoDownloadsTopic,
        "test-group"
      )

      received <- service.updates.take(1).compile.toList
        .timeout(1.second)
        .start
        .flatMap { fiber =>
          IO.sleep(50.millis) *>
            scheduleVideoDownloadsTopic.publish1(sampleScheduledVideoDownload) *>
            fiber.joinWithNever
        }

      _ <- IO.delay {
        received must have length 1
        received.head mustBe sampleScheduledVideoDownload
      }
    } yield ()
  }

  "run" should "start a fiber" in runIO {
    for {
      downloadProgressTopic <- Topic[IO, DownloadProgress]
      healthCheckTopic <- Topic[IO, HealthCheckMessage]
      scheduleVideoDownloadsTopic <- Topic[IO, ScheduledVideoDownload]

      service = new BackgroundServiceImpl[IO, Id](
        new StubApiSchedulingService(),
        new StubSubscriber[DownloadProgress],
        downloadProgressTopic,
        new StubSubscriber[HealthCheckMessage],
        healthCheckTopic,
        new StubSubscriber[ScheduledVideoDownload],
        scheduleVideoDownloadsTopic,
        "test-group"
      )

      fiber <- service.run
      _ <- IO.sleep(100.millis)
      _ <- fiber.cancel
    } yield ()
  }

  "publishToTopic" should "forward messages from subscriber to topic" in runIO {
    val progress = DownloadProgress("video-1", timestamp, 1024L)

    class EmittingSubscriber extends StubSubscriber[DownloadProgress] {
      override def subscribe(groupId: String): Stream[IO, CommittableRecord[Id, DownloadProgress]] = {
        subscribed = true
        Stream.emit(CommittableRecord[Id, DownloadProgress](progress, progress))
      }
    }

    for {
      downloadProgressTopic <- Topic[IO, DownloadProgress]
      healthCheckTopic <- Topic[IO, HealthCheckMessage]
      scheduleVideoDownloadsTopic <- Topic[IO, ScheduledVideoDownload]

      subscriber = new EmittingSubscriber()

      service = new BackgroundServiceImpl[IO, Id](
        new StubApiSchedulingService(),
        subscriber,
        downloadProgressTopic,
        new StubSubscriber[HealthCheckMessage],
        healthCheckTopic,
        new StubSubscriber[ScheduledVideoDownload],
        scheduleVideoDownloadsTopic,
        "test-group"
      )

      // Start receiving from topic before running the service
      receivedFiber <- downloadProgressTopic.subscribe(10).take(1).compile.toList
        .timeout(2.seconds)
        .start

      // Start the service
      fiber <- service.run

      received <- receivedFiber.joinWithNever

      _ <- fiber.cancel

      _ <- IO.delay {
        subscriber.subscribed mustBe true
        received must have length 1
        received.head mustBe progress
      }
    } yield ()
  }

  "updateScheduledVideoDownloads" should "batch and update download progress" in runIO {
    val apiSchedulingService = new StubApiSchedulingService()

    for {
      downloadProgressTopic <- Topic[IO, DownloadProgress]
      healthCheckTopic <- Topic[IO, HealthCheckMessage]
      scheduleVideoDownloadsTopic <- Topic[IO, ScheduledVideoDownload]

      service = new BackgroundServiceImpl[IO, Id](
        apiSchedulingService,
        new StubSubscriber[DownloadProgress],
        downloadProgressTopic,
        new StubSubscriber[HealthCheckMessage],
        healthCheckTopic,
        new StubSubscriber[ScheduledVideoDownload],
        scheduleVideoDownloadsTopic,
        "test-group"
      )

      // Start the service
      fiber <- service.run

      // Give the service time to start
      _ <- IO.sleep(100.millis)

      // Publish progress to the topic
      _ <- downloadProgressTopic.publish1(DownloadProgress("video-1", timestamp, 1024L))
      _ <- downloadProgressTopic.publish1(DownloadProgress("video-1", timestamp.plusSeconds(1), 2048L))
      _ <- downloadProgressTopic.publish1(DownloadProgress("video-2", timestamp, 512L))

      // Wait for batch window (5 seconds is the window, but we'll wait a bit extra)
      _ <- IO.sleep(6.seconds)

      _ <- fiber.cancel

      _ <- IO.delay {
        // Should have processed the progress updates
        apiSchedulingService.updatedProgress.size must be >= 1
      }
    } yield ()
  }

  "healthChecks topic" should "receive messages from health check subscriber" in runIO {
    val healthCheck = HealthCheckMessage("instance-1", timestamp)

    class EmittingHealthSubscriber extends StubSubscriber[HealthCheckMessage] {
      override def subscribe(groupId: String): Stream[IO, CommittableRecord[Id, HealthCheckMessage]] = {
        subscribed = true
        Stream.emit(CommittableRecord[Id, HealthCheckMessage](healthCheck, healthCheck))
      }
    }

    for {
      downloadProgressTopic <- Topic[IO, DownloadProgress]
      healthCheckTopic <- Topic[IO, HealthCheckMessage]
      scheduleVideoDownloadsTopic <- Topic[IO, ScheduledVideoDownload]

      subscriber = new EmittingHealthSubscriber()

      service = new BackgroundServiceImpl[IO, Id](
        new StubApiSchedulingService(),
        new StubSubscriber[DownloadProgress],
        downloadProgressTopic,
        subscriber,
        healthCheckTopic,
        new StubSubscriber[ScheduledVideoDownload],
        scheduleVideoDownloadsTopic,
        "test-group"
      )

      // Start receiving from topic
      receivedFiber <- healthCheckTopic.subscribe(10).take(1).compile.toList
        .timeout(2.seconds)
        .start

      // Start the service
      fiber <- service.run

      received <- receivedFiber.joinWithNever

      _ <- fiber.cancel

      _ <- IO.delay {
        subscriber.subscribed mustBe true
        received must have length 1
        received.head mustBe healthCheck
      }
    } yield ()
  }

  "scheduled video download updates topic" should "receive messages from subscriber" in runIO {
    class EmittingScheduledSubscriber extends StubSubscriber[ScheduledVideoDownload] {
      override def subscribe(groupId: String): Stream[IO, CommittableRecord[Id, ScheduledVideoDownload]] = {
        subscribed = true
        Stream.emit(CommittableRecord[Id, ScheduledVideoDownload](sampleScheduledVideoDownload, sampleScheduledVideoDownload))
      }
    }

    for {
      downloadProgressTopic <- Topic[IO, DownloadProgress]
      healthCheckTopic <- Topic[IO, HealthCheckMessage]
      scheduleVideoDownloadsTopic <- Topic[IO, ScheduledVideoDownload]

      subscriber = new EmittingScheduledSubscriber()

      service = new BackgroundServiceImpl[IO, Id](
        new StubApiSchedulingService(),
        new StubSubscriber[DownloadProgress],
        downloadProgressTopic,
        new StubSubscriber[HealthCheckMessage],
        healthCheckTopic,
        subscriber,
        scheduleVideoDownloadsTopic,
        "test-group"
      )

      // Start receiving from topic
      receivedFiber <- scheduleVideoDownloadsTopic.subscribe(10).take(1).compile.toList
        .timeout(2.seconds)
        .start

      // Start the service
      fiber <- service.run

      received <- receivedFiber.joinWithNever

      _ <- fiber.cancel

      _ <- IO.delay {
        subscriber.subscribed mustBe true
        received must have length 1
        received.head mustBe sampleScheduledVideoDownload
      }
    } yield ()
  }
}
