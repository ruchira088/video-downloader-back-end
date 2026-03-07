package com.ruchij.batch.services.detection

import cats.effect.IO
import cats.implicits._
import cats.~>
import com.ruchij.core.daos.duplicate.DuplicateVideoDao
import com.ruchij.core.daos.duplicate.models.DuplicateVideo
import com.ruchij.core.daos.hash.VideoPerceptualHashDao
import com.ruchij.core.daos.hash.models.VideoPerceptualHash
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.snapshot.SnapshotDao
import com.ruchij.core.daos.snapshot.models.Snapshot
import com.ruchij.core.services.hashing.PerceptualHashingService
import com.ruchij.core.services.repository.RepositoryService
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.test.Providers
import com.ruchij.core.types.{Clock, TimeUtils}
import fs2.Stream
import org.http4s.MediaType
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.collection.mutable
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

    override val uniqueVideoDurations: IO[Set[FiniteDuration]] = IO.pure(durations)
    override def getVideoIdsByDuration(duration: FiniteDuration): IO[Seq[String]] =
      IO.pure(videoIdsByDuration.getOrElse(duration, Seq.empty))
    override def findVideoHashesByDuration(duration: FiniteDuration): IO[Seq[VideoPerceptualHash]] =
      IO.pure(hashesByDuration.getOrElse(duration, Seq.empty))
    override def getByVideoId(videoId: String): IO[List[VideoPerceptualHash]] =
      IO.pure(hashesByVideoId.getOrElse(videoId, List.empty))
    override def insert(videoPerceptualHash: VideoPerceptualHash): IO[Int] =
      IO.delay { insertedHashes += videoPerceptualHash; 1 }
  }

  class StubDuplicateVideoDao(
    existingGroups: Map[String, Seq[DuplicateVideo]] = Map.empty
  ) extends DuplicateVideoDao[IO] {
    val insertedVideos: mutable.ListBuffer[DuplicateVideo] = mutable.ListBuffer.empty
    val deletedVideoIds: mutable.ListBuffer[String] = mutable.ListBuffer.empty

    override def insert(duplicateVideo: DuplicateVideo): IO[Int] =
      IO.delay { insertedVideos += duplicateVideo; 1 }
    override def delete(videoId: String): IO[Int] =
      IO.delay { deletedVideoIds += videoId; 1 }
    override def findByVideoId(videoId: String): IO[Option[DuplicateVideo]] = IO.pure(None)
    override def findByDuplicateGroupId(duplicateGroupId: String): IO[Seq[DuplicateVideo]] =
      IO.pure(existingGroups.getOrElse(duplicateGroupId, Seq.empty))
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

  private def createService(
    hashDao: StubVideoPerceptualHashDao = new StubVideoPerceptualHashDao(),
    dupDao: StubDuplicateVideoDao = new StubDuplicateVideoDao(),
    snapshotDao: SnapshotDao[IO] = new StubSnapshotDao(),
    hashingService: PerceptualHashingService[IO] = new StubPerceptualHashingService(),
    repositoryService: RepositoryService[IO] = new StubRepositoryService()
  ): BatchDuplicateDetectionServiceImpl[IO, IO] =
    new BatchDuplicateDetectionServiceImpl[IO, IO](
      hashingService, repositoryService, hashDao, dupDao, snapshotDao
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

  "DifferenceThreshold" should "be 0.2" in {
    BatchDuplicateDetectionService.DifferenceThreshold mustBe 0.2
  }
}
