package com.ruchij.batch.services.enrichment

import cats.effect.IO
import cats.~>
import com.ruchij.batch.services.snapshots.VideoSnapshotService
import com.ruchij.core.config.StorageConfiguration
import com.ruchij.core.daos.resource.FileResourceDao
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.snapshot.SnapshotDao
import com.ruchij.core.daos.snapshot.models.Snapshot
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.test.data.CoreTestData
import com.ruchij.core.types.RandomGenerator
import org.http4s.MediaType
import com.ruchij.core.types.TimeUtils
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.util.UUID
import scala.collection.mutable
import scala.concurrent.duration._

class VideoEnrichmentServiceImplSpec extends AnyFlatSpec with Matchers {

  implicit val transaction: IO ~> IO = new (IO ~> IO) {
    override def apply[A](fa: IO[A]): IO[A] = fa
  }

  private val timestamp = TimeUtils.instantOf(2024, 5, 15, 10, 30)
  private val storageConfiguration = StorageConfiguration("videos", "images", List.empty)

  private val testUuid = UUID.fromString("12345678-1234-1234-1234-123456789abc")

  private def stubUuidGenerator: RandomGenerator[IO, UUID] =
    new RandomGenerator[IO, UUID] {
      override def generate: IO[UUID] = IO.pure(testUuid)
    }

  class StubVideoSnapshotService(
    result: (String, FiniteDuration, String) => FileResource = (_, _, path) =>
      FileResource("snapshot-id", timestamp, path, MediaType.image.jpeg, 50000L)
  ) extends VideoSnapshotService[IO] {
    val capturedCalls: mutable.ListBuffer[(String, FiniteDuration, String)] = mutable.ListBuffer.empty

    override def takeSnapshot(
      videoFileKey: String,
      videoTimestamp: FiniteDuration,
      snapshotDestination: String
    ): IO[FileResource] = IO.delay {
      capturedCalls += ((videoFileKey, videoTimestamp, snapshotDestination))
      result(videoFileKey, videoTimestamp, snapshotDestination)
    }
  }

  class StubSnapshotDao extends SnapshotDao[IO] {
    val insertedSnapshots: mutable.ListBuffer[Snapshot] = mutable.ListBuffer.empty

    override def insert(snapshot: Snapshot): IO[Int] = IO.delay {
      insertedSnapshots += snapshot
      1
    }

    override def findByVideo(videoId: String, maybeUserId: Option[String]): IO[Seq[Snapshot]] = IO.pure(Seq.empty)

    override def hasPermission(snapshotFileResourceId: String, userId: String): IO[Boolean] = IO.pure(false)

    override def deleteByVideo(videoId: String): IO[Int] = IO.pure(0)
  }

  class StubFileResourceDao extends FileResourceDao[IO] {
    val insertedResources: mutable.ListBuffer[FileResource] = mutable.ListBuffer.empty

    override def insert(resource: FileResource): IO[Int] = IO.delay {
      insertedResources += resource
      1
    }

    override def update(id: String, size: Long): IO[Int] = IO.pure(1)

    override def getById(id: String): IO[Option[FileResource]] = IO.pure(None)

    override def findByPath(path: String): IO[Option[FileResource]] = IO.pure(None)

    override def deleteById(id: String): IO[Int] = IO.pure(1)
  }

  private def createService(
    videoSnapshotService: VideoSnapshotService[IO] = new StubVideoSnapshotService(),
    snapshotDao: SnapshotDao[IO] = new StubSnapshotDao(),
    fileResourceDao: FileResourceDao[IO] = new StubFileResourceDao()
  )(implicit uuidGenerator: RandomGenerator[IO, UUID]): VideoEnrichmentServiceImpl[IO, IO] = {
    new VideoEnrichmentServiceImpl[IO, IO](
      videoSnapshotService,
      snapshotDao,
      fileResourceDao,
      storageConfiguration
    )
  }

  "snapshotMediaType" should "return image/jpeg" in runIO {
    implicit val uuidGenerator: RandomGenerator[IO, UUID] = stubUuidGenerator
    val service = createService()

    IO.delay {
      service.snapshotMediaType mustBe MediaType.image.jpeg
    }
  }

  "videoSnapshots" should "create 12 snapshots for a video" in runIO {
    implicit val uuidGenerator: RandomGenerator[IO, UUID] = stubUuidGenerator

    val snapshotService = new StubVideoSnapshotService()
    val snapshotDao = new StubSnapshotDao()
    val fileResourceDao = new StubFileResourceDao()

    val service = createService(snapshotService, snapshotDao, fileResourceDao)
    val video = CoreTestData.YouTubeVideo

    service.videoSnapshots(video).map { snapshots =>
      snapshots.size mustBe 12
      snapshotService.capturedCalls.size mustBe 12
      snapshotDao.insertedSnapshots.size mustBe 12
      fileResourceDao.insertedResources.size mustBe 12
    }
  }

  it should "use correct video path" in runIO {
    implicit val uuidGenerator: RandomGenerator[IO, UUID] = stubUuidGenerator

    val snapshotService = new StubVideoSnapshotService()
    val service = createService(snapshotService)
    val video = CoreTestData.YouTubeVideo

    service.videoSnapshots(video).map { _ =>
      snapshotService.capturedCalls.foreach { case (videoPath, _, _) =>
        videoPath mustBe video.fileResource.path
      }
    }
  }

  it should "generate unique snapshot paths with video ID and timestamp" in runIO {
    implicit val uuidGenerator: RandomGenerator[IO, UUID] = stubUuidGenerator

    val snapshotService = new StubVideoSnapshotService()
    val service = createService(snapshotService)
    val video = CoreTestData.YouTubeVideo

    service.videoSnapshots(video).map { _ =>
      snapshotService.capturedCalls.foreach { case (_, _, snapshotPath) =>
        snapshotPath must startWith(s"${storageConfiguration.imageFolder}/${video.videoMetadata.id}-snapshot-")
        snapshotPath must endWith(".jpeg")
      }
    }
  }

  it should "set correct timestamps on snapshots" in runIO {
    implicit val uuidGenerator: RandomGenerator[IO, UUID] = stubUuidGenerator

    val snapshotService = new StubVideoSnapshotService()
    val service = createService(snapshotService)
    val video = CoreTestData.YouTubeVideo

    service.videoSnapshots(video).map { snapshots =>
      val timestamps = snapshots.map(_.videoTimestamp)

      timestamps.foreach { ts =>
        ts must be > 0.seconds
        ts must be < video.videoMetadata.duration
      }

      timestamps.size mustBe 12
      timestamps.toSet.size mustBe 12
    }
  }

  it should "set videoId on all snapshots" in runIO {
    implicit val uuidGenerator: RandomGenerator[IO, UUID] = stubUuidGenerator

    val snapshotDao = new StubSnapshotDao()
    val service = createService(snapshotDao = snapshotDao)
    val video = CoreTestData.YouTubeVideo

    service.videoSnapshots(video).map { _ =>
      snapshotDao.insertedSnapshots.foreach { snapshot =>
        snapshot.videoId mustBe video.videoMetadata.id
      }
    }
  }

  "snapshotFileResource" should "delegate to video snapshot service" in runIO {
    implicit val uuidGenerator: RandomGenerator[IO, UUID] = stubUuidGenerator

    val expectedResource = FileResource(
      "test-resource",
      timestamp,
      "/images/snapshot.jpg",
      MediaType.image.jpeg,
      25000L
    )

    val snapshotService = new StubVideoSnapshotService(
      result = (_, _, _) => expectedResource
    )

    val service = createService(snapshotService)

    service.snapshotFileResource("/videos/test.mp4", "/images/snapshot.jpg", 30.seconds).map { result =>
      result mustBe expectedResource
      snapshotService.capturedCalls.size mustBe 1
      snapshotService.capturedCalls.head mustBe (("/videos/test.mp4", 30.seconds, "/images/snapshot.jpg"))
    }
  }

  it should "handle different video timestamps" in runIO {
    implicit val uuidGenerator: RandomGenerator[IO, UUID] = stubUuidGenerator

    val snapshotService = new StubVideoSnapshotService()
    val service = createService(snapshotService)

    for {
      _ <- service.snapshotFileResource("/videos/video.mp4", "/images/snap1.jpg", 0.seconds)
      _ <- service.snapshotFileResource("/videos/video.mp4", "/images/snap2.jpg", 1.minute)
      _ <- service.snapshotFileResource("/videos/video.mp4", "/images/snap3.jpg", 1.hour)
    } yield {
      snapshotService.capturedCalls.size mustBe 3
      snapshotService.capturedCalls.map(_._2) mustBe List(0.seconds, 1.minute, 1.hour)
    }
  }

  "videoSnapshots" should "handle short videos" in runIO {
    implicit val uuidGenerator: RandomGenerator[IO, UUID] = stubUuidGenerator

    val snapshotService = new StubVideoSnapshotService()
    val service = createService(snapshotService)

    val shortVideo = CoreTestData.YouTubeVideo.copy(
      videoMetadata = CoreTestData.YouTubeVideo.videoMetadata.copy(duration = 13.seconds)
    )

    service.videoSnapshots(shortVideo).map { snapshots =>
      snapshots.size mustBe 12
      snapshots.foreach { snapshot =>
        snapshot.videoTimestamp must be > 0.seconds
        snapshot.videoTimestamp must be < shortVideo.videoMetadata.duration
      }
    }
  }

  it should "handle long videos" in runIO {
    implicit val uuidGenerator: RandomGenerator[IO, UUID] = stubUuidGenerator

    val snapshotService = new StubVideoSnapshotService()
    val service = createService(snapshotService)

    val longVideo = CoreTestData.YouTubeVideo.copy(
      videoMetadata = CoreTestData.YouTubeVideo.videoMetadata.copy(duration = 3.hours)
    )

    service.videoSnapshots(longVideo).map { snapshots =>
      snapshots.size mustBe 12
      snapshots.foreach { snapshot =>
        snapshot.videoTimestamp must be > 0.seconds
        snapshot.videoTimestamp must be < longVideo.videoMetadata.duration
      }
    }
  }

  it should "insert file resources and snapshots in transaction" in runIO {
    implicit val uuidGenerator: RandomGenerator[IO, UUID] = stubUuidGenerator

    val snapshotDao = new StubSnapshotDao()
    val fileResourceDao = new StubFileResourceDao()
    val service = createService(snapshotDao = snapshotDao, fileResourceDao = fileResourceDao)
    val video = CoreTestData.YouTubeVideo

    service.videoSnapshots(video).map { _ =>
      fileResourceDao.insertedResources.size mustBe 12
      snapshotDao.insertedSnapshots.size mustBe 12

      snapshotDao.insertedSnapshots.zip(fileResourceDao.insertedResources).foreach {
        case (snapshot, fileResource) =>
          snapshot.fileResource.id mustBe fileResource.id
      }
    }
  }

  it should "generate evenly spaced timestamps" in runIO {
    implicit val uuidGenerator: RandomGenerator[IO, UUID] = stubUuidGenerator

    val snapshotService = new StubVideoSnapshotService()
    val service = createService(snapshotService)

    val video = CoreTestData.YouTubeVideo.copy(
      videoMetadata = CoreTestData.YouTubeVideo.videoMetadata.copy(duration = 130.seconds)
    )

    service.videoSnapshots(video).map { snapshots =>
      val timestamps = snapshots.map(_.videoTimestamp.toMillis).sorted
      val expectedPeriod = 130000L / 13

      timestamps.zipWithIndex.foreach { case (ts, idx) =>
        ts mustBe ((idx + 1) * expectedPeriod)
      }
    }
  }
}
