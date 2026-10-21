package com.ruchij.api.services.scheduling

import cats.effect.{IO, Ref}
import cats.implicits._
import com.ruchij.api.services.scheduling.VideoPermissionCache.Settings
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.types.{Clock, TimeUtils}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.time.Instant
import scala.concurrent.duration._

class VideoPermissionCacheSpec extends AnyFlatSpec with Matchers {
  private val startTime = TimeUtils.instantOf(2024, 5, 15, 10, 0)
  private val settings = Settings(maxEntries = 2, permittedTtl = 5.minutes, deniedTtl = 10.seconds)

  private def setUp(permitted: Set[String]): IO[(Ref[IO, Instant], Ref[IO, List[String]], VideoPermissionCache[IO])] =
    for {
      timeRef <- Ref.of[IO, Instant](startTime)
      lookupsRef <- Ref.of[IO, List[String]](List.empty)
      clock = new Clock[IO] { override def timestamp: IO[Instant] = timeRef.get }
      cache <- VideoPermissionCache.create[IO](
        videoId => lookupsRef.update(_ :+ videoId).as(permitted.contains(videoId)),
        settings
      )(implicitly, clock)
    } yield (timeRef, lookupsRef, cache)

  "VideoPermissionCache" should "look up a video once and serve repeated checks from the cache" in runIO {
    for {
      (_, lookupsRef, cache) <- setUp(Set("video-1"))
      results <- List("video-1", "video-2", "video-1", "video-2").traverse(cache.hasPermission)
      lookups <- lookupsRef.get
    } yield {
      results mustBe List(true, false, true, false)
      lookups mustBe List("video-1", "video-2")
    }
  }

  it should "re-check a denied video after the denied TTL so newly scheduled videos are picked up" in runIO {
    for {
      (timeRef, lookupsRef, cache) <- setUp(Set.empty)
      _ <- cache.hasPermission("video-1")
      _ <- timeRef.update(_.plusSeconds(5))
      _ <- cache.hasPermission("video-1")
      _ <- timeRef.update(_.plusSeconds(6))
      _ <- cache.hasPermission("video-1")
      lookups <- lookupsRef.get
    } yield lookups mustBe List("video-1", "video-1")
  }

  it should "re-check a permitted video after the permitted TTL" in runIO {
    for {
      (timeRef, lookupsRef, cache) <- setUp(Set("video-1"))
      _ <- cache.hasPermission("video-1")
      _ <- timeRef.update(_.plusSeconds(60))
      _ <- cache.hasPermission("video-1")
      _ <- timeRef.update(_.plusSeconds(300))
      _ <- cache.hasPermission("video-1")
      lookups <- lookupsRef.get
    } yield lookups mustBe List("video-1", "video-1")
  }

  it should "stay bounded by evicting the least recently checked entry" in runIO {
    for {
      (timeRef, lookupsRef, cache) <- setUp(Set("video-1", "video-2", "video-3"))
      _ <- cache.hasPermission("video-1")
      _ <- timeRef.update(_.plusSeconds(1))
      _ <- cache.hasPermission("video-2")
      _ <- timeRef.update(_.plusSeconds(1))
      _ <- cache.hasPermission("video-3")
      _ <- cache.hasPermission("video-2")
      _ <- cache.hasPermission("video-3")
      _ <- cache.hasPermission("video-1")
      lookups <- lookupsRef.get
    } yield lookups mustBe List("video-1", "video-2", "video-3", "video-1")
  }

  it should "share a single look up between concurrent checks of the same video" in runIO {
    for {
      lookupsRef <- Ref.of[IO, Int](0)
      cache <- VideoPermissionCache.create[IO](
        _ => lookupsRef.update(_ + 1).productR(IO.sleep(100.milliseconds)).as(true),
        settings
      )
      results <- List.fill(10)("video-1").parTraverse(cache.hasPermission)
      lookups <- lookupsRef.get
    } yield {
      results mustBe List.fill(10)(true)
      lookups mustBe 1
    }
  }

  it should "not cache a failed look up" in runIO {
    for {
      lookupsRef <- Ref.of[IO, Int](0)
      cache <- VideoPermissionCache.create[IO](
        _ =>
          lookupsRef.getAndUpdate(_ + 1).flatMap { count =>
            if (count == 0) IO.raiseError(new RuntimeException("Database unavailable")) else IO.pure(true)
          },
        settings
      )
      firstResult <- cache.hasPermission("video-1").attempt
      secondResult <- cache.hasPermission("video-1")
    } yield {
      firstResult.isLeft mustBe true
      secondResult mustBe true
    }
  }
}
