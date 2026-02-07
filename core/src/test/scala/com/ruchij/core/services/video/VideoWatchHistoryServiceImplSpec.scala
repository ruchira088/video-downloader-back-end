package com.ruchij.core.services.video

import cats.effect.IO
import cats.~>
import com.ruchij.core.daos.videowatchhistory.VideoWatchHistoryDao
import com.ruchij.core.daos.videowatchhistory.models.{DetailedVideoWatchHistory, VideoWatchHistory}
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.types.RandomGenerator
import java.time.Instant
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.util.UUID
import scala.collection.mutable
import scala.concurrent.duration._
import scala.language.postfixOps

class VideoWatchHistoryServiceImplSpec extends AnyFlatSpec with Matchers {

  class InMemoryVideoWatchHistoryDao extends VideoWatchHistoryDao[IO] {
    val store: mutable.Map[String, VideoWatchHistory] = mutable.Map.empty

    override def insert(videoWatchHistory: VideoWatchHistory): IO[Unit] =
      IO.delay {
        store.put(videoWatchHistory.id, videoWatchHistory)
        ()
      }

    override def findBy(userId: String, pageSize: Int, pageNumber: Int): IO[List[DetailedVideoWatchHistory]] =
      IO.pure(List.empty)

    override def findBy(userId: String, videoId: String, pageSize: Int, pageNumber: Int): IO[List[DetailedVideoWatchHistory]] =
      IO.pure(List.empty)

    override def findLastUpdatedAfter(userId: String, videoId: String, timestamp: Instant): IO[Option[VideoWatchHistory]] =
      IO.delay {
        store.values.find { history =>
          history.userId == userId &&
          history.videoId == videoId &&
          history.lastUpdatedAt.isAfter(timestamp)
        }
      }

    override def update(updatedVideoWatchHistory: VideoWatchHistory): IO[Unit] =
      IO.delay {
        store.put(updatedVideoWatchHistory.id, updatedVideoWatchHistory)
        ()
      }

    override def deleteBy(videoId: String): IO[Int] =
      IO.delay {
        val toRemove = store.values.filter(_.videoId == videoId).map(_.id).toList
        toRemove.foreach(store.remove)
        toRemove.size
      }
  }

  implicit val transaction: IO ~> IO = new (IO ~> IO) {
    override def apply[A](fa: IO[A]): IO[A] = fa
  }

  implicit val uuidRandomGenerator: RandomGenerator[IO, UUID] = new RandomGenerator[IO, UUID] {
    override def generate: IO[UUID] = IO.delay(UUID.randomUUID())
  }

  "getWatchHistoryByUser" should "return empty list when no history exists" in runIO {
    val dao = new InMemoryVideoWatchHistoryDao
    val service = new VideoWatchHistoryServiceImpl[IO, IO](dao)

    for {
      result <- service.getWatchHistoryByUser("user-1", 10, 0)
      _ <- IO.delay {
        result mustBe List.empty
      }
    } yield ()
  }

  "addWatchHistory" should "insert new watch history when no recent history exists" in runIO {
    val dao = new InMemoryVideoWatchHistoryDao
    val service = new VideoWatchHistoryServiceImpl[IO, IO](dao)
    val timestamp = Instant.now()

    for {
      _ <- service.addWatchHistory("user-1", "video-1", timestamp, 30 seconds)
      _ <- IO.delay {
        dao.store.size mustBe 1
        val history = dao.store.values.head
        history.userId mustBe "user-1"
        history.videoId mustBe "video-1"
        history.duration mustBe (30 seconds)
      }
    } yield ()
  }

  it should "update existing watch history when recent history exists" in runIO {
    val dao = new InMemoryVideoWatchHistoryDao
    val service = new VideoWatchHistoryServiceImpl[IO, IO](dao)
    val timestamp = Instant.now()

    val existingHistory = VideoWatchHistory(
      "existing-id",
      "user-1",
      "video-1",
      timestamp.minus(java.time.Duration.ofMinutes(3)),
      timestamp.minusSeconds(10),
      60 seconds
    )

    for {
      _ <- dao.insert(existingHistory)
      _ <- service.addWatchHistory("user-1", "video-1", timestamp, 30 seconds)
      _ <- IO.delay {
        dao.store.size mustBe 1
        val history = dao.store("existing-id")
        history.duration mustBe (90 seconds)
        history.lastUpdatedAt mustBe timestamp
      }
    } yield ()
  }

  it should "create new history when existing history is too old" in runIO {
    val dao = new InMemoryVideoWatchHistoryDao
    val service = new VideoWatchHistoryServiceImpl[IO, IO](dao)
    val timestamp = Instant.now()

    val oldHistory = VideoWatchHistory(
      "old-id",
      "user-1",
      "video-1",
      timestamp.minus(java.time.Duration.ofMinutes(10)),
      timestamp.minus(java.time.Duration.ofMinutes(10)),
      60 seconds
    )

    for {
      _ <- dao.insert(oldHistory)
      _ <- service.addWatchHistory("user-1", "video-1", timestamp, 30 seconds)
      _ <- IO.delay {
        dao.store.size mustBe 2
      }
    } yield ()
  }

  it should "handle different users independently" in runIO {
    val dao = new InMemoryVideoWatchHistoryDao
    val service = new VideoWatchHistoryServiceImpl[IO, IO](dao)
    val timestamp = Instant.now()

    for {
      _ <- service.addWatchHistory("user-1", "video-1", timestamp, 30 seconds)
      _ <- service.addWatchHistory("user-2", "video-1", timestamp, 45 seconds)
      _ <- IO.delay {
        dao.store.size mustBe 2
        val histories = dao.store.values.toList
        histories.map(_.userId).toSet mustBe Set("user-1", "user-2")
      }
    } yield ()
  }

  it should "handle different videos independently" in runIO {
    val dao = new InMemoryVideoWatchHistoryDao
    val service = new VideoWatchHistoryServiceImpl[IO, IO](dao)
    val timestamp = Instant.now()

    for {
      _ <- service.addWatchHistory("user-1", "video-1", timestamp, 30 seconds)
      _ <- service.addWatchHistory("user-1", "video-2", timestamp, 45 seconds)
      _ <- IO.delay {
        dao.store.size mustBe 2
        val histories = dao.store.values.toList
        histories.map(_.videoId).toSet mustBe Set("video-1", "video-2")
      }
    } yield ()
  }
}
