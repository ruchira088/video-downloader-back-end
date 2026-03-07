package com.ruchij.api.services.detection

import cats.effect.IO
import cats.~>
import com.ruchij.core.daos.duplicate.DuplicateVideoDao
import com.ruchij.core.daos.duplicate.models.DuplicateVideo
import com.ruchij.core.daos.hash.VideoPerceptualHashDao
import com.ruchij.core.daos.hash.models.VideoPerceptualHash
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.types.TimeUtils
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.collection.mutable
import scala.concurrent.duration._

class ApiDuplicateDetectionServiceImplSpec extends AnyFlatSpec with Matchers {

  private val timestamp = TimeUtils.instantOf(2024, 5, 15, 10, 30)

  private implicit val identityTransaction: IO ~> IO = cats.arrow.FunctionK.id[IO]

  class StubVideoPerceptualHashDao extends VideoPerceptualHashDao[IO] {
    val deletedHashVideoIds: mutable.ListBuffer[String] = mutable.ListBuffer.empty

    override val uniqueVideoDurations: IO[Set[FiniteDuration]] = IO.pure(Set.empty)
    override def getVideoIdsByDuration(duration: FiniteDuration): IO[Seq[String]] = IO.pure(Seq.empty)
    override def findVideoHashesByDuration(duration: FiniteDuration): IO[Seq[VideoPerceptualHash]] = IO.pure(Seq.empty)
    override def getByVideoId(videoId: String): IO[List[VideoPerceptualHash]] = IO.pure(List.empty)
    override def insert(videoPerceptualHash: VideoPerceptualHash): IO[Int] = IO.pure(1)
    override def deleteByVideoId(videoId: String): IO[Int] =
      IO.delay { deletedHashVideoIds += videoId; 1 }
  }

  class StubDuplicateVideoDao(
    getAllResult: Seq[DuplicateVideo] = Seq.empty,
    initialVideos: Seq[DuplicateVideo] = Seq.empty,
    findByGroupResult: Seq[DuplicateVideo] = Seq.empty,
    groupIdsResult: Seq[String] = Seq.empty
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
        else findByGroupResult
      }
    override def getAll(offset: Int, limit: Int): IO[Seq[DuplicateVideo]] = IO.pure(getAllResult)
    override def duplicateGroupIds: IO[Seq[String]] = IO.pure(groupIdsResult)
    override def deleteAll: IO[Int] = IO.pure(0)
  }

  private def createService(
    dao: StubDuplicateVideoDao,
    hashDao: StubVideoPerceptualHashDao = new StubVideoPerceptualHashDao()
  ): ApiDuplicateDetectionServiceImpl[IO, IO] =
    new ApiDuplicateDetectionServiceImpl[IO, IO](dao, hashDao)

  "findDuplicateVideos" should "return grouped duplicate videos" in runIO {
    val duplicates = Seq(
      DuplicateVideo("video-1", "group-a", timestamp),
      DuplicateVideo("video-2", "group-a", timestamp),
      DuplicateVideo("video-3", "group-b", timestamp),
      DuplicateVideo("video-4", "group-b", timestamp)
    )
    val dao = new StubDuplicateVideoDao(getAllResult = duplicates)
    val service = createService(dao = dao)

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
    val service = createService(dao = dao)

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
    val service = createService(dao = dao)

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

    val dao = new StubDuplicateVideoDao() {
      override def getAll(offset: Int, limit: Int): IO[Seq[DuplicateVideo]] = IO.delay {
        capturedOffset = offset
        capturedLimit = limit
        Seq.empty
      }
    }
    val service = createService(dao = dao)

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
    val service = createService(dao = dao)

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
    val service = createService(dao = dao)

    for {
      result <- service.getDuplicateVideoGroup("group-a")
    } yield {
      result.size mustBe 2
      result.map(_.videoId) mustBe Seq("video-1", "video-2")
    }
  }

  it should "return empty sequence for non-existent group" in runIO {
    val dao = new StubDuplicateVideoDao()
    val service = createService(dao = dao)

    for {
      result <- service.getDuplicateVideoGroup("non-existent")
    } yield result mustBe empty
  }

  "duplicateVideoGroups" should "return all group IDs" in runIO {
    val dao = new StubDuplicateVideoDao(groupIdsResult = Seq("group-a", "group-b", "group-c"))
    val service = createService(dao = dao)

    for {
      result <- service.duplicateVideoGroups
    } yield result mustBe Seq("group-a", "group-b", "group-c")
  }

  it should "return empty sequence when no groups exist" in runIO {
    val dao = new StubDuplicateVideoDao()
    val service = createService(dao = dao)

    for {
      result <- service.duplicateVideoGroups
    } yield result mustBe empty
  }

  "deleteVideo" should "return None when video is not a duplicate" in runIO {
    val dao = new StubDuplicateVideoDao()
    val service = createService(dao = dao)

    for {
      result <- service.deleteVideo("non-existent")
    } yield {
      result mustBe None
    }
  }

  it should "delete a video and remove the group when only one member remains" in runIO {
    val dup1 = DuplicateVideo("v1", "v1", timestamp)
    val dup2 = DuplicateVideo("v2", "v1", timestamp)

    val dao = new StubDuplicateVideoDao(initialVideos = Seq(dup1, dup2))
    val hashDao = new StubVideoPerceptualHashDao()
    val service = createService(dao = dao, hashDao = hashDao)

    for {
      result <- service.deleteVideo("v2")
    } yield {
      result mustBe Some(dup2)
      dao.deletedVideoIds must contain("v2")
      dao.deletedVideoIds must contain("v1")
      dao.videos mustBe empty
      hashDao.deletedHashVideoIds must contain("v2")
    }
  }

  it should "delete a video and keep the group intact when multiple members remain" in runIO {
    val dup1 = DuplicateVideo("v1", "v1", timestamp)
    val dup2 = DuplicateVideo("v2", "v1", timestamp)
    val dup3 = DuplicateVideo("v3", "v1", timestamp)

    val dao = new StubDuplicateVideoDao(initialVideos = Seq(dup1, dup2, dup3))
    val hashDao = new StubVideoPerceptualHashDao()
    val service = createService(dao = dao, hashDao = hashDao)

    for {
      result <- service.deleteVideo("v3")
    } yield {
      result mustBe Some(dup3)
      dao.deletedVideoIds must contain("v3")
      dao.videos.map(_.videoId).toSet must contain allOf ("v1", "v2")
      hashDao.deletedHashVideoIds must contain("v3")
    }
  }

  it should "re-group with new group ID when the group leader is deleted" in runIO {
    val dup1 = DuplicateVideo("a-video", "a-video", timestamp)
    val dup2 = DuplicateVideo("b-video", "a-video", timestamp)
    val dup3 = DuplicateVideo("c-video", "a-video", timestamp)

    val dao = new StubDuplicateVideoDao(initialVideos = Seq(dup1, dup2, dup3))
    val hashDao = new StubVideoPerceptualHashDao()
    val service = createService(dao = dao, hashDao = hashDao)

    for {
      result <- service.deleteVideo("a-video")
    } yield {
      result mustBe Some(dup1)
      val remaining = dao.videos.toList
      remaining.map(_.videoId).toSet mustBe Set("b-video", "c-video")
      remaining.foreach(_.duplicateGroupId mustBe "b-video")
      hashDao.deletedHashVideoIds must contain("a-video")
    }
  }
}
