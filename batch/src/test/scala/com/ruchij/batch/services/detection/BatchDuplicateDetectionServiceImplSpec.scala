package com.ruchij.batch.services.detection

import cats.data.NonEmptyList
import cats.effect.IO
import cats.implicits._
import cats.~>
import com.ruchij.batch.services.video.BatchVideoService
import com.ruchij.core.daos.duplicate.DuplicateVideoDao
import com.ruchij.core.daos.duplicate.models.DuplicateVideo
import com.ruchij.core.daos.hash.VideoPerceptualHashDao
import com.ruchij.core.daos.hash.models.VideoPerceptualHash
import com.ruchij.core.daos.permission.VideoPermissionDao
import com.ruchij.core.daos.permission.models.VideoPermission
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.snapshot.SnapshotDao
import com.ruchij.core.daos.snapshot.models.Snapshot
import com.ruchij.core.daos.scheduling.models.RangeValue
import com.ruchij.core.daos.video.VideoDao
import com.ruchij.core.daos.video.models.{Video, VideoSiteSummary}
import com.ruchij.core.daos.videometadata.models.{CustomVideoSite, VideoMetadata, VideoSite}
import com.ruchij.core.services.models.{Order, SortBy}
import com.ruchij.core.services.hashing.PerceptualHashingService
import com.ruchij.core.services.repository.RepositoryService
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.test.Providers
import com.ruchij.core.types.{Clock, TimeUtils}
import fs2.Stream
import org.http4s.{MediaType, Uri}
import org.http4s.implicits.http4sLiteralsSyntax
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.collection.mutable
import java.time.Instant
import scala.concurrent.duration._
import scala.language.postfixOps

class BatchDuplicateDetectionServiceImplSpec extends AnyFlatSpec with Matchers {

  private val timestamp = TimeUtils.instantOf(2024, 5, 15, 10, 30)
  private implicit val identityTransaction: IO ~> IO = cats.arrow.FunctionK.id[IO]
  private implicit val stubClock: Clock[IO] = Providers.stubClock[IO](timestamp)

  class StubRepositoryService(
    readResult: Option[Stream[IO, Byte]] = Some(Stream.emits("image-data".getBytes))
  ) extends RepositoryService[IO] {
    override type BackedType = String
    override def write(key: Key, data: Stream[IO, Byte]): Stream[IO, Nothing] = Stream.empty
    override def read(key: Key, start: Option[Long], end: Option[Long]): IO[Option[Stream[IO, Byte]]] =
      IO.pure(readResult)
    override def size(key: Key): IO[Option[Long]] = IO.pure(Some(1000L))
    override def list(key: Key): Stream[IO, Key] = Stream.empty
    override def exists(key: Key): IO[Boolean] = IO.pure(true)
    override def backedType(key: Key): IO[BackedType] = IO.pure("file")
    override def delete(key: Key): IO[Boolean] = IO.pure(true)
    override def fileType(key: Key): IO[Option[MediaType]] = IO.pure(Some(MediaType.image.png))
  }

  class StubVideoPerceptualHashDao(
    durations: Set[FiniteDuration] = Set.empty,
    videoIdsByDuration: Map[FiniteDuration, Seq[String]] = Map.empty,
    hashesByDuration: Map[FiniteDuration, Seq[VideoPerceptualHash]] = Map.empty,
    hashesByVideoId: Map[String, List[VideoPerceptualHash]] = Map.empty
  ) extends VideoPerceptualHashDao[IO] {
    val insertedHashes: mutable.ListBuffer[VideoPerceptualHash] = mutable.ListBuffer.empty
    val deletedHashVideoIds: mutable.ListBuffer[String] = mutable.ListBuffer.empty

    override val uniqueVideoDurations: IO[Set[FiniteDuration]] = IO.pure(durations)
    override def getVideoIdsByDuration(duration: FiniteDuration): IO[Seq[String]] =
      IO.pure(videoIdsByDuration.getOrElse(duration, Seq.empty))
    override def findVideoHashesByDuration(duration: FiniteDuration): IO[Seq[VideoPerceptualHash]] =
      IO.pure(hashesByDuration.getOrElse(duration, Seq.empty))
    override def getByVideoId(videoId: String): IO[List[VideoPerceptualHash]] =
      IO.pure(hashesByVideoId.getOrElse(videoId, List.empty))
    override def insert(videoPerceptualHash: VideoPerceptualHash): IO[Int] =
      IO.delay { insertedHashes += videoPerceptualHash; 1 }
    override def deleteByVideoId(videoId: String): IO[Int] =
      IO.delay { deletedHashVideoIds += videoId; 1 }
  }

  class StubDuplicateVideoDao(
    initialVideos: Seq[DuplicateVideo] = Seq.empty,
    existingGroups: Map[String, Seq[DuplicateVideo]] = Map.empty
  ) extends DuplicateVideoDao[IO] {
    val videos: mutable.ListBuffer[DuplicateVideo] = mutable.ListBuffer.from(initialVideos)
    val insertedVideos: mutable.ListBuffer[DuplicateVideo] = mutable.ListBuffer.empty
    val deletedVideoIds: mutable.ListBuffer[String] = mutable.ListBuffer.empty

    override def insert(duplicateVideo: DuplicateVideo): IO[Int] =
      IO.delay { insertedVideos += duplicateVideo; videos += duplicateVideo; 1 }
    override def delete(videoId: String): IO[Int] =
      IO.delay { deletedVideoIds += videoId; videos.filterInPlace(_.videoId != videoId); 1 }
    override def findByVideoId(videoId: String): IO[Option[DuplicateVideo]] =
      IO.delay(videos.find(_.videoId == videoId))
    override def findByDuplicateGroupId(duplicateGroupId: String): IO[Seq[DuplicateVideo]] =
      IO.delay {
        val fromState = videos.filter(_.duplicateGroupId == duplicateGroupId).toSeq
        if (fromState.nonEmpty) fromState
        else existingGroups.getOrElse(duplicateGroupId, Seq.empty)
      }
    override def getAll(offset: Int, limit: Int): IO[Seq[DuplicateVideo]] = IO.pure(Seq.empty)
    override def duplicateGroupIds: IO[Seq[String]] = IO.pure(Seq.empty)
    override def deleteAll: IO[Int] = IO.pure(0)
  }

  class StubSnapshotDao(
    snapshotsByVideo: Map[String, Seq[Snapshot]] = Map.empty
  ) extends SnapshotDao[IO] {
    override def insert(snapshot: Snapshot): IO[Int] = IO.pure(1)
    override def findByVideo(videoId: String, maybeUserId: Option[String]): IO[Seq[Snapshot]] =
      IO.pure(snapshotsByVideo.getOrElse(videoId, Seq.empty))
    override def hasPermission(snapshotFileResourceId: String, userId: String): IO[Boolean] = IO.pure(true)
    override def isSnapshotFileResource(fileResourceId: String): IO[Boolean] = IO.pure(true)
    override def deleteByVideo(videoId: String): IO[Int] = IO.pure(0)
  }

  class StubPerceptualHashingService(
    hashResults: Map[Any, BigInt] = Map.empty,
    compareResults: Map[(BigInt, BigInt), Double] = Map.empty
  ) extends PerceptualHashingService[IO] {
    override def hashImage(image: Stream[IO, Byte]): IO[BigInt] =
      IO.pure(hashResults.values.headOption.getOrElse(BigInt(42L)))
    override def compareHashes(hashA: BigInt, hashB: BigInt): IO[Double] =
      IO.pure(compareResults.getOrElse((hashA, hashB), compareResults.getOrElse((hashB, hashA), 0.5)))
  }

  private def notImplemented[A]: IO[A] = IO.raiseError(new NotImplementedError("Not used by these tests"))

  class StubVideoDao(videos: Seq[Video] = Seq.empty) extends VideoDao[IO] {
    val playlistTransfers: mutable.ListBuffer[(String, String)] = mutable.ListBuffer.empty

    override def findByIds(videoIds: NonEmptyList[String]): IO[Seq[Video]] =
      IO.pure(videos.filter(video => videoIds.toList.contains(video.id)))
    override def transferPlaylistMemberships(fromVideoId: String, toVideoId: String): IO[Int] =
      IO.delay { playlistTransfers += (fromVideoId -> toVideoId); 1 }

    override def insert(
      videoMetadataId: String,
      videoFileResourceId: String,
      timestamp: Instant,
      watchTime: FiniteDuration
    ): IO[Int] = notImplemented
    override def search(
      term: Option[String],
      videoUrls: Option[NonEmptyList[Uri]],
      durationRange: RangeValue[FiniteDuration],
      sizeRange: RangeValue[Long],
      pageNumber: Int,
      pageSize: Int,
      sortBy: SortBy,
      order: Order,
      videoSites: Option[NonEmptyList[VideoSite]],
      maybeUserId: Option[String]
    ): IO[Seq[Video]] = notImplemented
    override def incrementWatchTime(videoId: String, finiteDuration: FiniteDuration): IO[Option[FiniteDuration]] =
      notImplemented
    override def findById(videoId: String, maybeUserId: Option[String]): IO[Option[Video]] = notImplemented
    override def findByVideoFileResourceId(fileResourceId: String): IO[Option[Video]] = notImplemented
    override def findByVideoPath(videoPath: String): IO[Option[Video]] = notImplemented
    override def deleteById(videoId: String): IO[Int] = notImplemented
    override def hasVideoFilePermission(videoFileResourceId: String, userId: String): IO[Boolean] = notImplemented
    override def isVideoFileResourceExist(videoFileResourceId: String): IO[Boolean] = notImplemented
    override val count: IO[Int] = notImplemented
    override val duration: IO[FiniteDuration] = notImplemented
    override val size: IO[Long] = notImplemented
    override val sites: IO[Set[VideoSite]] = notImplemented
    override val siteSummaries: IO[Seq[VideoSiteSummary]] = notImplemented
  }

  class StubVideoPermissionDao(initialPermissions: Seq[VideoPermission] = Seq.empty) extends VideoPermissionDao[IO] {
    val permissions: mutable.ListBuffer[VideoPermission] = mutable.ListBuffer.from(initialPermissions)

    override def insert(videoPermission: VideoPermission): IO[Int] =
      IO.delay {
        val exists = permissions.exists { permission =>
          permission.scheduledVideoDownloadId == videoPermission.scheduledVideoDownloadId &&
          permission.userId == videoPermission.userId
        }
        if (exists) 0 else { permissions += videoPermission; 1 }
      }
    override def find(userId: Option[String], scheduledVideoId: Option[String]): IO[Seq[VideoPermission]] =
      IO.delay {
        permissions.filter { permission =>
          userId.forall(_ == permission.userId) && scheduledVideoId.forall(_ == permission.scheduledVideoDownloadId)
        }.toSeq
      }
    override def delete(maybeUserId: Option[String], maybeScheduledVideoId: Option[String]): IO[Int] = notImplemented
  }

  class StubBatchVideoService(failingVideoIds: Set[String] = Set.empty) extends BatchVideoService[IO] {
    val deletions: mutable.ListBuffer[(String, Boolean)] = mutable.ListBuffer.empty

    override def deleteById(videoId: String, deleteVideoFile: Boolean): IO[Video] =
      if (failingVideoIds.contains(videoId)) IO.raiseError(new IllegalStateException(s"Unable to delete $videoId"))
      else IO.delay(deletions += (videoId -> deleteVideoFile)) *> IO.pure(video(videoId, 0, timestamp))

    override def insert(videoMetadataKey: String, fileResourceKey: String): IO[Video] = notImplemented
    override def incrementWatchTime(videoId: String, duration: FiniteDuration): IO[FiniteDuration] = notImplemented
    override def fetchByVideoFileResourceId(videoFileResourceId: String): IO[Video] = notImplemented
    override def update(videoId: String, size: Long): IO[Video] = notImplemented
  }

  private def video(videoId: String, size: Long, createdAt: Instant): Video = {
    val thumbnail = FileResource(s"$videoId-thumbnail", createdAt, s"/opt/images/$videoId.jpg", MediaType.image.jpeg, 10)
    val videoMetadata =
      VideoMetadata(uri"https://example.com/video", videoId, CustomVideoSite.SpankBang, videoId, 5 minutes, size, thumbnail)

    Video(
      videoMetadata,
      FileResource(s"$videoId-file", createdAt, s"/opt/videos/$videoId.mp4", MediaType.video.mp4, size),
      createdAt,
      0 seconds
    )
  }

  private def createService(
    hashDao: StubVideoPerceptualHashDao = new StubVideoPerceptualHashDao(),
    dupDao: StubDuplicateVideoDao = new StubDuplicateVideoDao(),
    snapshotDao: SnapshotDao[IO] = new StubSnapshotDao(),
    hashingService: PerceptualHashingService[IO] = new StubPerceptualHashingService(),
    repositoryService: RepositoryService[IO] = new StubRepositoryService(),
    videoDao: VideoDao[IO] = new StubVideoDao(),
    videoPermissionDao: VideoPermissionDao[IO] = new StubVideoPermissionDao(),
    batchVideoService: BatchVideoService[IO] = new StubBatchVideoService()
  ): BatchDuplicateDetectionServiceImpl[IO, IO] =
    new BatchDuplicateDetectionServiceImpl[IO, IO](
      hashingService,
      repositoryService,
      hashDao,
      dupDao,
      snapshotDao,
      videoDao,
      videoPermissionDao,
      batchVideoService
    )

  "detect" should "return empty map when no video durations exist" in runIO {
    val service = createService()

    for {
      result <- service.detect
    } yield result mustBe empty
  }

  it should "detect duplicates for videos with similar hashes" in runIO {
    val hash1 = VideoPerceptualHash("v1", timestamp, 5 minutes, BigInt(100L), 150 seconds)
    val hash2 = VideoPerceptualHash("v2", timestamp, 5 minutes, BigInt(101L), 150 seconds)

    val hashDao = new StubVideoPerceptualHashDao(
      durations = Set(5 minutes),
      videoIdsByDuration = Map((5 minutes) -> Seq("v1", "v2")),
      hashesByDuration = Map((5 minutes) -> Seq(hash1, hash2))
    )
    val hashingService = new StubPerceptualHashingService(
      compareResults = Map((BigInt(100L), BigInt(101L)) -> 0.05)
    )
    val service = createService(hashDao = hashDao, hashingService = hashingService)

    for {
      result <- service.detect
    } yield {
      result.contains(5 minutes) mustBe true
      result(5 minutes).size mustBe 1
      result(5 minutes).head mustBe Set("v1", "v2")
    }
  }

  it should "not group videos with high hash distance" in runIO {
    val hash1 = VideoPerceptualHash("v1", timestamp, 5 minutes, BigInt(100L), 150 seconds)
    val hash2 = VideoPerceptualHash("v2", timestamp, 5 minutes, BigInt(999L), 150 seconds)

    val hashDao = new StubVideoPerceptualHashDao(
      durations = Set(5 minutes),
      videoIdsByDuration = Map((5 minutes) -> Seq("v1", "v2")),
      hashesByDuration = Map((5 minutes) -> Seq(hash1, hash2))
    )
    val hashingService = new StubPerceptualHashingService(
      compareResults = Map((BigInt(100L), BigInt(999L)) -> 0.8)
    )
    val service = createService(hashDao = hashDao, hashingService = hashingService)

    for {
      result <- service.detect
    } yield {
      result(5 minutes) mustBe empty
    }
  }

  it should "compute hashes for videos without existing hashes" in runIO {
    val snapshotTimestamps = com.ruchij.batch.services.enrichment.VideoEnrichmentService.snapshotTimestamps(5 minutes, 12)
    val middleTimestamp = snapshotTimestamps.sorted.toList(snapshotTimestamps.size / 2)
    val snapshotFR = FileResource("snap-1", timestamp, "/opt/snapshots/snap-1.png", MediaType.image.png, 5000)
    val snapshot = Snapshot("v1", snapshotFR, middleTimestamp)

    val hashDao = new StubVideoPerceptualHashDao(
      durations = Set(5 minutes),
      videoIdsByDuration = Map((5 minutes) -> Seq("v1")),
      hashesByDuration = Map((5 minutes) -> Seq.empty)
    )
    val snapshotDao = new StubSnapshotDao(snapshotsByVideo = Map("v1" -> Seq(snapshot)))
    val service = createService(hashDao = hashDao, snapshotDao = snapshotDao)

    for {
      result <- service.detect
    } yield {
      result(5 minutes) mustBe empty
      hashDao.insertedHashes.size mustBe 1
      hashDao.insertedHashes.head.videoId mustBe "v1"
    }
  }

  it should "skip hash computation for videos that already have hashes" in runIO {
    val existingHash = VideoPerceptualHash("v1", timestamp, 5 minutes, BigInt(42L), 150 seconds)

    val hashDao = new StubVideoPerceptualHashDao(
      durations = Set(5 minutes),
      videoIdsByDuration = Map((5 minutes) -> Seq("v1")),
      hashesByDuration = Map((5 minutes) -> Seq(existingHash))
    )
    val service = createService(hashDao = hashDao)

    for {
      result <- service.detect
    } yield {
      result(5 minutes) mustBe empty
      hashDao.insertedHashes mustBe empty
    }
  }

  it should "handle videos without snapshots gracefully" in runIO {
    val hashDao = new StubVideoPerceptualHashDao(
      durations = Set(5 minutes),
      videoIdsByDuration = Map((5 minutes) -> Seq("v1")),
      hashesByDuration = Map((5 minutes) -> Seq.empty)
    )
    val snapshotDao = new StubSnapshotDao()
    val service = createService(hashDao = hashDao, snapshotDao = snapshotDao)

    for {
      result <- service.detect
    } yield {
      result(5 minutes) mustBe empty
    }
  }

  it should "group three similar videos together" in runIO {
    val hash1 = VideoPerceptualHash("v1", timestamp, 5 minutes, BigInt(100L), 150 seconds)
    val hash2 = VideoPerceptualHash("v2", timestamp, 5 minutes, BigInt(101L), 150 seconds)
    val hash3 = VideoPerceptualHash("v3", timestamp, 5 minutes, BigInt(102L), 150 seconds)

    val hashDao = new StubVideoPerceptualHashDao(
      durations = Set(5 minutes),
      videoIdsByDuration = Map((5 minutes) -> Seq("v1", "v2", "v3")),
      hashesByDuration = Map((5 minutes) -> Seq(hash1, hash2, hash3))
    )
    val hashingService = new StubPerceptualHashingService(
      compareResults = Map(
        (BigInt(100L), BigInt(101L)) -> 0.05,
        (BigInt(100L), BigInt(102L)) -> 0.10
      )
    )
    val service = createService(hashDao = hashDao, hashingService = hashingService)

    for {
      result <- service.detect
    } yield {
      result(5 minutes).size mustBe 1
      result(5 minutes).head mustBe Set("v1", "v2", "v3")
    }
  }

  it should "separate distinct groups when videos are not similar" in runIO {
    val hash1 = VideoPerceptualHash("v1", timestamp, 5 minutes, BigInt(100L), 150 seconds)
    val hash2 = VideoPerceptualHash("v2", timestamp, 5 minutes, BigInt(101L), 150 seconds)
    val hash3 = VideoPerceptualHash("v3", timestamp, 5 minutes, BigInt(900L), 150 seconds)
    val hash4 = VideoPerceptualHash("v4", timestamp, 5 minutes, BigInt(901L), 150 seconds)

    val hashDao = new StubVideoPerceptualHashDao(
      durations = Set(5 minutes),
      videoIdsByDuration = Map((5 minutes) -> Seq("v1", "v2", "v3", "v4")),
      hashesByDuration = Map((5 minutes) -> Seq(hash1, hash2, hash3, hash4))
    )
    val hashingService = new StubPerceptualHashingService(
      compareResults = Map(
        (BigInt(100L), BigInt(101L)) -> 0.05,
        (BigInt(100L), BigInt(900L)) -> 0.8,
        (BigInt(100L), BigInt(901L)) -> 0.85,
        (BigInt(900L), BigInt(901L)) -> 0.03
      )
    )
    val service = createService(hashDao = hashDao, hashingService = hashingService)

    for {
      result <- service.detect
    } yield {
      val groups = result(5 minutes)
      groups.size mustBe 2
      groups must contain(Set("v1", "v2"))
      groups must contain(Set("v3", "v4"))
    }
  }

  it should "handle exactly at the threshold boundary (0.2)" in runIO {
    val hash1 = VideoPerceptualHash("v1", timestamp, 5 minutes, BigInt(100L), 150 seconds)
    val hash2 = VideoPerceptualHash("v2", timestamp, 5 minutes, BigInt(200L), 150 seconds)

    val hashDao = new StubVideoPerceptualHashDao(
      durations = Set(5 minutes),
      videoIdsByDuration = Map((5 minutes) -> Seq("v1", "v2")),
      hashesByDuration = Map((5 minutes) -> Seq(hash1, hash2))
    )
    val hashingService = new StubPerceptualHashingService(
      compareResults = Map((BigInt(100L), BigInt(200L)) -> 0.2)
    )
    val service = createService(hashDao = hashDao, hashingService = hashingService)

    for {
      result <- service.detect
    } yield {
      result(5 minutes).size mustBe 1
      result(5 minutes).head mustBe Set("v1", "v2")
    }
  }

  it should "not group when just above the threshold" in runIO {
    val hash1 = VideoPerceptualHash("v1", timestamp, 5 minutes, BigInt(100L), 150 seconds)
    val hash2 = VideoPerceptualHash("v2", timestamp, 5 minutes, BigInt(200L), 150 seconds)

    val hashDao = new StubVideoPerceptualHashDao(
      durations = Set(5 minutes),
      videoIdsByDuration = Map((5 minutes) -> Seq("v1", "v2")),
      hashesByDuration = Map((5 minutes) -> Seq(hash1, hash2))
    )
    val hashingService = new StubPerceptualHashingService(
      compareResults = Map((BigInt(100L), BigInt(200L)) -> 0.21)
    )
    val service = createService(hashDao = hashDao, hashingService = hashingService)

    for {
      result <- service.detect
    } yield {
      result(5 minutes) mustBe empty
    }
  }

  "run" should "persist detected duplicates to the database" in runIO {
    val hash1 = VideoPerceptualHash("v1", timestamp, 5 minutes, BigInt(100L), 150 seconds)
    val hash2 = VideoPerceptualHash("v2", timestamp, 5 minutes, BigInt(101L), 150 seconds)

    val hashDao = new StubVideoPerceptualHashDao(
      durations = Set(5 minutes),
      videoIdsByDuration = Map((5 minutes) -> Seq("v1", "v2")),
      hashesByDuration = Map((5 minutes) -> Seq(hash1, hash2))
    )
    val dupDao = new StubDuplicateVideoDao()
    val hashingService = new StubPerceptualHashingService(
      compareResults = Map((BigInt(100L), BigInt(101L)) -> 0.05)
    )
    val service = createService(hashDao = hashDao, dupDao = dupDao, hashingService = hashingService)

    for {
      _ <- service.run
    } yield {
      dupDao.insertedVideos.size mustBe 2
      dupDao.insertedVideos.map(_.videoId).toSet mustBe Set("v1", "v2")
      dupDao.insertedVideos.foreach(_.duplicateGroupId mustBe "v1")
    }
  }

  it should "skip inserting already-existing duplicate records" in runIO {
    val hash1 = VideoPerceptualHash("v1", timestamp, 5 minutes, BigInt(100L), 150 seconds)
    val hash2 = VideoPerceptualHash("v2", timestamp, 5 minutes, BigInt(101L), 150 seconds)

    val hashDao = new StubVideoPerceptualHashDao(
      durations = Set(5 minutes),
      videoIdsByDuration = Map((5 minutes) -> Seq("v1", "v2")),
      hashesByDuration = Map((5 minutes) -> Seq(hash1, hash2))
    )
    val existingRecords = Seq(
      DuplicateVideo("v1", "v1", timestamp),
      DuplicateVideo("v2", "v1", timestamp)
    )
    val dupDao = new StubDuplicateVideoDao(existingGroups = Map("v1" -> existingRecords))
    val hashingService = new StubPerceptualHashingService(
      compareResults = Map((BigInt(100L), BigInt(101L)) -> 0.05)
    )
    val service = createService(hashDao = hashDao, dupDao = dupDao, hashingService = hashingService)

    for {
      _ <- service.run
    } yield {
      dupDao.insertedVideos mustBe empty
    }
  }

  it should "handle empty detection results" in runIO {
    val service = createService()

    service.run
  }

  it should "use the smallest video ID as the group ID" in runIO {
    val hash1 = VideoPerceptualHash("c-video", timestamp, 5 minutes, BigInt(100L), 150 seconds)
    val hash2 = VideoPerceptualHash("a-video", timestamp, 5 minutes, BigInt(101L), 150 seconds)
    val hash3 = VideoPerceptualHash("b-video", timestamp, 5 minutes, BigInt(102L), 150 seconds)

    val hashDao = new StubVideoPerceptualHashDao(
      durations = Set(5 minutes),
      videoIdsByDuration = Map((5 minutes) -> Seq("c-video", "a-video", "b-video")),
      hashesByDuration = Map((5 minutes) -> Seq(hash1, hash2, hash3))
    )
    val dupDao = new StubDuplicateVideoDao()
    val hashingService = new StubPerceptualHashingService(
      compareResults = Map(
        (BigInt(100L), BigInt(101L)) -> 0.05,
        (BigInt(100L), BigInt(102L)) -> 0.10
      )
    )
    val service = createService(hashDao = hashDao, dupDao = dupDao, hashingService = hashingService)

    for {
      _ <- service.run
    } yield {
      dupDao.insertedVideos.foreach(_.duplicateGroupId mustBe "a-video")
    }
  }

  it should "delete old records and re-insert when no existing group records found" in runIO {
    val hash1 = VideoPerceptualHash("v1", timestamp, 5 minutes, BigInt(100L), 150 seconds)
    val hash2 = VideoPerceptualHash("v2", timestamp, 5 minutes, BigInt(101L), 150 seconds)

    val hashDao = new StubVideoPerceptualHashDao(
      durations = Set(5 minutes),
      videoIdsByDuration = Map((5 minutes) -> Seq("v1", "v2")),
      hashesByDuration = Map((5 minutes) -> Seq(hash1, hash2))
    )
    val dupDao = new StubDuplicateVideoDao()
    val hashingService = new StubPerceptualHashingService(
      compareResults = Map((BigInt(100L), BigInt(101L)) -> 0.05)
    )
    val service = createService(hashDao = hashDao, dupDao = dupDao, hashingService = hashingService)

    for {
      _ <- service.run
    } yield {
      dupDao.deletedVideoIds.toSet mustBe Set("v1", "v2")
      dupDao.insertedVideos.size mustBe 2
    }
  }

  it should "not delete when existing group records are found" in runIO {
    val hash1 = VideoPerceptualHash("v1", timestamp, 5 minutes, BigInt(100L), 150 seconds)
    val hash2 = VideoPerceptualHash("v2", timestamp, 5 minutes, BigInt(101L), 150 seconds)

    val hashDao = new StubVideoPerceptualHashDao(
      durations = Set(5 minutes),
      videoIdsByDuration = Map((5 minutes) -> Seq("v1", "v2")),
      hashesByDuration = Map((5 minutes) -> Seq(hash1, hash2))
    )
    val existingRecords = Seq(
      DuplicateVideo("v1", "v1", timestamp),
      DuplicateVideo("v2", "v1", timestamp)
    )
    val dupDao = new StubDuplicateVideoDao(existingGroups = Map("v1" -> existingRecords))
    val hashingService = new StubPerceptualHashingService(
      compareResults = Map((BigInt(100L), BigInt(101L)) -> 0.05)
    )
    val service = createService(hashDao = hashDao, dupDao = dupDao, hashingService = hashingService)

    for {
      _ <- service.run
    } yield {
      dupDao.deletedVideoIds mustBe empty
      dupDao.insertedVideos mustBe empty
    }
  }

  private def similarVideosHashDao(videoIds: String*): StubVideoPerceptualHashDao = {
    val hashes = videoIds.toList.map(videoId => VideoPerceptualHash(videoId, timestamp, 5 minutes, BigInt(100L), 150 seconds))

    new StubVideoPerceptualHashDao(
      durations = Set(5 minutes),
      videoIdsByDuration = Map((5 minutes) -> videoIds),
      hashesByDuration = Map((5 minutes) -> hashes)
    )
  }

  private def snapshotPath(videoId: String, snapshotTimestamp: FiniteDuration): String =
    s"/opt/snapshots/$videoId-${snapshotTimestamp.toMillis}.png"

  /** Gives each video a snapshot at every verification timestamp, except the ones in `missing`. */
  private def verificationSnapshotDao(videoIds: Seq[String], missing: Set[String]): StubSnapshotDao =
    new StubSnapshotDao(
      videoIds.map { videoId =>
        videoId -> BatchDuplicateDetectionService.verificationSnapshotTimestamps(5 minutes).flatMap { snapshotTimestamp =>
          val path = snapshotPath(videoId, snapshotTimestamp)

          if (missing.contains(path)) None
          else Some(Snapshot(videoId, FileResource(path, timestamp, path, MediaType.image.png, 10), snapshotTimestamp))
        }
      }.toMap
    )

  /** Reads each file as its own path, so that the hashing service can tell the snapshots apart. */
  class PathRepositoryService extends StubRepositoryService {
    override def read(key: Key, start: Option[Long], end: Option[Long]): IO[Option[Stream[IO, Byte]]] =
      IO.pure(Some(Stream.emits(key.getBytes)))
  }

  /** Hashes every image alike, except the ones at `differentPaths`. Equal hashes are 0 apart and others 1 apart. */
  class PathHashingService(differentPaths: Set[String] = Set.empty) extends PerceptualHashingService[IO] {
    override def hashImage(image: Stream[IO, Byte]): IO[BigInt] =
      image.through(fs2.text.utf8.decode).compile.string.map { path =>
        if (differentPaths.contains(path)) BigInt(999L) else BigInt(100L)
      }
    override def compareHashes(hashA: BigInt, hashB: BigInt): IO[Double] = IO.pure(if (hashA == hashB) 0.0 else 1.0)
  }

  private def autoDeletionService(
    videos: Seq[Video],
    dupDao: StubDuplicateVideoDao = new StubDuplicateVideoDao(),
    hashDao: Option[StubVideoPerceptualHashDao] = None,
    videoDao: Option[StubVideoDao] = None,
    videoPermissionDao: VideoPermissionDao[IO] = new StubVideoPermissionDao(),
    batchVideoService: BatchVideoService[IO],
    differentSnapshotPaths: Set[String] = Set.empty,
    missingSnapshotPaths: Set[String] = Set.empty
  ): BatchDuplicateDetectionServiceImpl[IO, IO] = {
    val videoIds = videos.map(_.id)

    createService(
      hashDao.getOrElse(similarVideosHashDao(videoIds: _*)),
      dupDao,
      verificationSnapshotDao(videoIds, missingSnapshotPaths),
      new PathHashingService(differentSnapshotPaths),
      new PathRepositoryService,
      videoDao.getOrElse(new StubVideoDao(videos)),
      videoPermissionDao,
      batchVideoService
    )
  }

  it should "delete the smaller duplicate along with its file, after handing its playlists to the kept one" in runIO {
    val videos = Seq(video("v1", 100, timestamp), video("v2", 200, timestamp))
    val hashDao = similarVideosHashDao("v1", "v2")
    val dupDao = new StubDuplicateVideoDao()
    val videoDao = new StubVideoDao(videos)
    val permissions = Seq(VideoPermission(timestamp, "v1", "user-1"), VideoPermission(timestamp, "v2", "user-1"))
    val videoPermissionDao = new StubVideoPermissionDao(permissions)
    val batchVideoService = new StubBatchVideoService()

    val service =
      autoDeletionService(videos, dupDao, Some(hashDao), Some(videoDao), videoPermissionDao, batchVideoService)

    for {
      _ <- service.run
    } yield {
      batchVideoService.deletions.toList mustBe List("v1" -> true)
      videoPermissionDao.permissions.toList mustBe permissions
      videoDao.playlistTransfers.toList mustBe List("v1" -> "v2")
      hashDao.deletedHashVideoIds.toList mustBe List("v1")
      dupDao.videos mustBe empty
    }
  }

  it should "delete the older duplicate when the files are the same size" in runIO {
    val batchVideoService = new StubBatchVideoService()
    val service =
      autoDeletionService(
        Seq(video("v1", 100, timestamp.plusSeconds(60)), video("v2", 100, timestamp)),
        batchVideoService = batchVideoService
      )

    for {
      _ <- service.run
    } yield batchVideoService.deletions.toList mustBe List("v2" -> true)
  }

  it should "keep only the largest video of a group of three" in runIO {
    val batchVideoService = new StubBatchVideoService()
    val service =
      autoDeletionService(
        Seq(video("v1", 300, timestamp), video("v2", 500, timestamp), video("v3", 100, timestamp)),
        batchVideoService = batchVideoService
      )

    for {
      _ <- service.run
    } yield batchVideoService.deletions.map(_._1).toSet mustBe Set("v1", "v3")
  }

  it should "still delete the rest of the group when deleting one duplicate fails" in runIO {
    val batchVideoService = new StubBatchVideoService(failingVideoIds = Set("v1"))
    val service =
      autoDeletionService(
        Seq(video("v1", 100, timestamp), video("v2", 200, timestamp), video("v3", 500, timestamp)),
        batchVideoService = batchVideoService
      )

    for {
      _ <- service.run
    } yield batchVideoService.deletions.toList mustBe List("v2" -> true)
  }

  it should "keep a duplicate that different users hold, leaving the group for review" in runIO {
    val dupDao = new StubDuplicateVideoDao()
    val videoPermissionDao = new StubVideoPermissionDao(
      Seq(VideoPermission(timestamp, "v1", "user-1"), VideoPermission(timestamp, "v2", "user-2"))
    )
    val batchVideoService = new StubBatchVideoService()

    val service =
      autoDeletionService(
        Seq(video("v1", 100, timestamp), video("v2", 200, timestamp)),
        dupDao,
        videoPermissionDao = videoPermissionDao,
        batchVideoService = batchVideoService
      )

    for {
      _ <- service.run
    } yield {
      batchVideoService.deletions mustBe empty
      videoPermissionDao.permissions.size mustBe 2
      dupDao.videos.map(_.videoId).toSet mustBe Set("v1", "v2")
    }
  }

  it should "keep a duplicate when one of its verification snapshots differs from the kept video's" in runIO {
    val dupDao = new StubDuplicateVideoDao()
    val batchVideoService = new StubBatchVideoService()
    val lastTimestamp = BatchDuplicateDetectionService.verificationSnapshotTimestamps(5 minutes).last

    val service =
      autoDeletionService(
        Seq(video("v1", 100, timestamp), video("v2", 200, timestamp)),
        dupDao,
        batchVideoService = batchVideoService,
        differentSnapshotPaths = Set(snapshotPath("v1", lastTimestamp))
      )

    for {
      _ <- service.run
    } yield {
      batchVideoService.deletions mustBe empty
      dupDao.videos.map(_.videoId).toSet mustBe Set("v1", "v2")
    }
  }

  it should "only delete the duplicates that match the kept video directly, not through another duplicate" in runIO {
    val batchVideoService = new StubBatchVideoService()
    val firstTimestamp = BatchDuplicateDetectionService.verificationSnapshotTimestamps(5 minutes).head

    val service =
      autoDeletionService(
        Seq(video("v1", 100, timestamp), video("v2", 200, timestamp), video("v3", 500, timestamp)),
        batchVideoService = batchVideoService,
        differentSnapshotPaths = Set(snapshotPath("v1", firstTimestamp))
      )

    for {
      _ <- service.run
    } yield batchVideoService.deletions.toList mustBe List("v2" -> true)
  }

  it should "keep a duplicate when a verification snapshot is missing" in runIO {
    val batchVideoService = new StubBatchVideoService()
    val middleTimestamp = BatchDuplicateDetectionService.verificationSnapshotTimestamps(5 minutes)(1)

    val service =
      autoDeletionService(
        Seq(video("v1", 100, timestamp), video("v2", 200, timestamp)),
        batchVideoService = batchVideoService,
        missingSnapshotPaths = Set(snapshotPath("v2", middleTimestamp))
      )

    for {
      _ <- service.run
    } yield batchVideoService.deletions mustBe empty
  }

  "verificationSnapshotTimestamps" should "be a quarter, half and three quarters of the way through the snapshots" in {
    val snapshotTimestamps =
      com.ruchij.batch.services.enrichment.VideoEnrichmentService.snapshotTimestamps(13 minutes, 12).sorted

    BatchDuplicateDetectionService.verificationSnapshotTimestamps(13 minutes) mustBe
      Seq(snapshotTimestamps(3), snapshotTimestamps(6), snapshotTimestamps(9))
  }

  "videoToKeep" should "pick the video with the largest file" in {
    val videos = Seq(video("v1", 100, timestamp), video("v2", 300, timestamp), video("v3", 200, timestamp))

    BatchDuplicateDetectionService.videoToKeep(videos).map(_.id) mustBe Some("v2")
  }

  it should "pick the newest video when the files are the same size" in {
    val videos = Seq(video("v1", 100, timestamp.plusSeconds(60)), video("v2", 100, timestamp))

    BatchDuplicateDetectionService.videoToKeep(videos).map(_.id) mustBe Some("v1")
  }

  it should "pick the same video whatever the order, when size and age are the same" in {
    val videos = Seq(video("v1", 100, timestamp), video("v2", 100, timestamp))

    BatchDuplicateDetectionService.videoToKeep(videos).map(_.id) mustBe Some("v2")
    BatchDuplicateDetectionService.videoToKeep(videos.reverse).map(_.id) mustBe Some("v2")
  }

  it should "pick nothing from no videos" in {
    BatchDuplicateDetectionService.videoToKeep(Seq.empty) mustBe None
  }

  "DifferenceThreshold" should "be 0.2" in {
    BatchDuplicateDetectionService.DifferenceThreshold mustBe 0.2
  }
}
