package com.ruchij.api.services.detection

import cats.effect.IO
import cats.~>
import com.ruchij.core.daos.duplicate.DuplicateVideoDao
import com.ruchij.core.daos.duplicate.models.DuplicateVideo
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.types.TimeUtils
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class ApiDuplicateDetectionServiceImplSpec extends AnyFlatSpec with Matchers {

  private val timestamp = TimeUtils.instantOf(2024, 5, 15, 10, 30)

  private implicit val identityTransaction: IO ~> IO = cats.arrow.FunctionK.id[IO]

  class StubDuplicateVideoDao(
    getAllResult: Seq[DuplicateVideo] = Seq.empty,
    findByGroupResult: Seq[DuplicateVideo] = Seq.empty,
    groupIdsResult: Seq[String] = Seq.empty
  ) extends DuplicateVideoDao[IO] {
    override def insert(duplicateVideo: DuplicateVideo): IO[Int] = IO.pure(1)
    override def delete(videoId: String): IO[Int] = IO.pure(1)
    override def findByVideoId(videoId: String): IO[Option[DuplicateVideo]] = IO.pure(None)
    override def findByDuplicateGroupId(duplicateGroupId: String): IO[Seq[DuplicateVideo]] = IO.pure(findByGroupResult)
    override def getAll(offset: Int, limit: Int): IO[Seq[DuplicateVideo]] = IO.pure(getAllResult)
    override def duplicateGroupIds: IO[Seq[String]] = IO.pure(groupIdsResult)
    override def deleteAll: IO[Int] = IO.pure(0)
  }

  private def createService(dao: DuplicateVideoDao[IO]): ApiDuplicateDetectionServiceImpl[IO, IO] =
    new ApiDuplicateDetectionServiceImpl[IO, IO](dao)

  "findDuplicateVideos" should "return grouped duplicate videos" in runIO {
    val duplicates = Seq(
      DuplicateVideo("video-1", "group-a", timestamp),
      DuplicateVideo("video-2", "group-a", timestamp),
      DuplicateVideo("video-3", "group-b", timestamp),
      DuplicateVideo("video-4", "group-b", timestamp)
    )
    val dao = new StubDuplicateVideoDao(getAllResult = duplicates)
    val service = createService(dao)

    for {
      result <- service.findDuplicateVideos(0, 25)
    } yield {
      result.size mustBe 2
      result("group-a").map(_.videoId) mustBe Set("video-1", "video-2")
      result("group-b").map(_.videoId) mustBe Set("video-3", "video-4")
    }
  }

  it should "return empty set when no duplicates exist" in runIO {
    val dao = new StubDuplicateVideoDao()
    val service = createService(dao)

    for {
      result <- service.findDuplicateVideos(0, 10)
    } yield result mustBe empty
  }

  it should "handle a single group correctly" in runIO {
    val duplicates = Seq(
      DuplicateVideo("video-1", "group-x", timestamp),
      DuplicateVideo("video-2", "group-x", timestamp),
      DuplicateVideo("video-3", "group-x", timestamp)
    )
    val dao = new StubDuplicateVideoDao(getAllResult = duplicates)
    val service = createService(dao)

    for {
      result <- service.findDuplicateVideos(0, 10)
    } yield {
      result.size mustBe 1
      result("group-x").map(_.videoId) mustBe Set("video-1", "video-2", "video-3")
    }
  }

  it should "correctly verify offset and limit are used" in runIO {
    var capturedOffset = -1
    var capturedLimit = -1

    val dao = new DuplicateVideoDao[IO] {
      override def insert(duplicateVideo: DuplicateVideo): IO[Int] = IO.pure(1)
      override def delete(videoId: String): IO[Int] = IO.pure(1)
      override def findByVideoId(videoId: String): IO[Option[DuplicateVideo]] = IO.pure(None)
      override def findByDuplicateGroupId(duplicateGroupId: String): IO[Seq[DuplicateVideo]] = IO.pure(Seq.empty)
      override def getAll(offset: Int, limit: Int): IO[Seq[DuplicateVideo]] = IO.delay {
        capturedOffset = offset
        capturedLimit = limit
        Seq.empty
      }
      override def duplicateGroupIds: IO[Seq[String]] = IO.pure(Seq.empty)
      override def deleteAll: IO[Int] = IO.pure(0)
    }
    val service = createService(dao)

    for {
      _ <- service.findDuplicateVideos(20, 5)
    } yield {
      capturedOffset mustBe 20
      capturedLimit mustBe 5
    }
  }

  it should "handle multiple groups with varying sizes" in runIO {
    val duplicates = Seq(
      DuplicateVideo("v1", "g1", timestamp),
      DuplicateVideo("v2", "g2", timestamp),
      DuplicateVideo("v3", "g2", timestamp),
      DuplicateVideo("v4", "g3", timestamp),
      DuplicateVideo("v5", "g3", timestamp),
      DuplicateVideo("v6", "g3", timestamp)
    )
    val dao = new StubDuplicateVideoDao(getAllResult = duplicates)
    val service = createService(dao)

    for {
      result <- service.findDuplicateVideos(0, 100)
    } yield {
      result.size mustBe 3
      result("g1").map(_.videoId) mustBe Set("v1")
      result("g2").map(_.videoId) mustBe Set("v2", "v3")
      result("g3").map(_.videoId) mustBe Set("v4", "v5", "v6")
    }
  }

  "getDuplicateVideoGroup" should "return videos for a specific group" in runIO {
    val groupVideos = Seq(
      DuplicateVideo("video-1", "group-a", timestamp),
      DuplicateVideo("video-2", "group-a", timestamp)
    )
    val dao = new StubDuplicateVideoDao(findByGroupResult = groupVideos)
    val service = createService(dao)

    for {
      result <- service.getDuplicateVideoGroup("group-a")
    } yield {
      result.size mustBe 2
      result.map(_.videoId) mustBe Seq("video-1", "video-2")
    }
  }

  it should "return empty sequence for non-existent group" in runIO {
    val dao = new StubDuplicateVideoDao()
    val service = createService(dao)

    for {
      result <- service.getDuplicateVideoGroup("non-existent")
    } yield result mustBe empty
  }

  "duplicateVideoGroups" should "return all group IDs" in runIO {
    val dao = new StubDuplicateVideoDao(groupIdsResult = Seq("group-a", "group-b", "group-c"))
    val service = createService(dao)

    for {
      result <- service.duplicateVideoGroups
    } yield result mustBe Seq("group-a", "group-b", "group-c")
  }

  it should "return empty sequence when no groups exist" in runIO {
    val dao = new StubDuplicateVideoDao()
    val service = createService(dao)

    for {
      result <- service.duplicateVideoGroups
    } yield result mustBe empty
  }
}
