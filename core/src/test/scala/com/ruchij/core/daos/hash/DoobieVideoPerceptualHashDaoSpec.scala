package com.ruchij.core.daos.hash

import cats.effect.IO
import cats.implicits._
import cats.~>
import com.ruchij.core.daos.hash.models.VideoPerceptualHash
import com.ruchij.core.daos.resource.DoobieFileResourceDao
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.video.DoobieVideoDao
import com.ruchij.core.daos.videometadata.DoobieVideoMetadataDao
import com.ruchij.core.daos.videometadata.models.{CustomVideoSite, VideoMetadata}
import com.ruchij.core.external.embedded.EmbeddedCoreResourcesProvider
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.types.Clock
import doobie.ConnectionIO
import org.http4s.MediaType
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps

class DoobieVideoPerceptualHashDaoSpec extends AnyFlatSpec with Matchers {

  private def insertVideo(videoId: String, duration: FiniteDuration, timestamp: java.time.Instant): ConnectionIO[Unit] = {
    val thumbnailId = s"$videoId-thumb"
    val thumbnail = FileResource(thumbnailId, timestamp, s"/opt/images/$thumbnailId.jpg", MediaType.image.jpeg, 1000)
    val videoMetadata = VideoMetadata(
      org.http4s.Uri.unsafeFromString(s"https://spankbang.com/video/$videoId"),
      videoId,
      CustomVideoSite.SpankBang,
      s"Video $videoId",
      duration,
      50000,
      thumbnail
    )
    val videoFileResource = FileResource(videoId, timestamp, s"/opt/videos/$videoId.mp4", MediaType.video.mp4, 50000)

    for {
      _ <- DoobieFileResourceDao.insert(thumbnail)
      _ <- DoobieVideoMetadataDao.insert(videoMetadata)
      _ <- DoobieFileResourceDao.insert(videoFileResource)
      _ <- DoobieVideoDao.insert(videoId, videoFileResource.id, timestamp, 0 seconds)
    } yield ()
  }

  def runTest(testFn: (ConnectionIO ~> IO) => IO[Unit]): Unit =
    runIO {
      new EmbeddedCoreResourcesProvider[IO].transactor.use { transaction =>
        testFn(transaction)
      }
    }

  "DoobieVideoPerceptualHashDao" should "return empty unique durations when no videos exist" in runTest { transaction =>
    for {
      durations <- transaction(DoobieVideoPerceptualHashDao.uniqueVideoDurations)
      _ <- IO.delay { durations mustBe empty }
    } yield ()
  }

  it should "return unique video durations from the video table" in runTest { transaction =>
    for {
      timestamp <- Clock[IO].timestamp
      _ <- transaction(insertVideo("video-1", 5 minutes, timestamp))
      _ <- transaction(insertVideo("video-2", 5 minutes, timestamp))
      _ <- transaction(insertVideo("video-3", 10 minutes, timestamp))

      durations <- transaction(DoobieVideoPerceptualHashDao.uniqueVideoDurations)
      _ <- IO.delay {
        durations mustBe Set(5 minutes, 10 minutes)
      }
    } yield ()
  }

  it should "get video IDs by duration" in runTest { transaction =>
    for {
      timestamp <- Clock[IO].timestamp
      _ <- transaction(insertVideo("video-1", 5 minutes, timestamp))
      _ <- transaction(insertVideo("video-2", 5 minutes, timestamp))
      _ <- transaction(insertVideo("video-3", 10 minutes, timestamp))

      fiveMinVideos <- transaction(DoobieVideoPerceptualHashDao.getVideoIdsByDuration(5 minutes))
      _ <- IO.delay {
        fiveMinVideos.toSet mustBe Set("video-1", "video-2")
      }

      tenMinVideos <- transaction(DoobieVideoPerceptualHashDao.getVideoIdsByDuration(10 minutes))
      _ <- IO.delay {
        tenMinVideos mustBe Seq("video-3")
      }

      noVideos <- transaction(DoobieVideoPerceptualHashDao.getVideoIdsByDuration(15 minutes))
      _ <- IO.delay { noVideos mustBe empty }
    } yield ()
  }

  it should "insert and retrieve a perceptual hash" in runTest { transaction =>
    for {
      timestamp <- Clock[IO].timestamp
      _ <- transaction(insertVideo("video-1", 5 minutes, timestamp))

      hash = VideoPerceptualHash("video-1", timestamp, 5 minutes, BigInt(123456789L), 150 seconds)
      insertResult <- transaction(DoobieVideoPerceptualHashDao.insert(hash))
      _ <- IO.delay { insertResult mustBe 1 }

      hashes <- transaction(DoobieVideoPerceptualHashDao.getByVideoId("video-1"))
      _ <- IO.delay {
        hashes.size mustBe 1
        hashes.head.videoId mustBe "video-1"
        hashes.head.duration mustBe (5 minutes)
        hashes.head.snapshotTimestamp mustBe (150 seconds)
      }
    } yield ()
  }

  it should "find video hashes by duration" in runTest { transaction =>
    for {
      timestamp <- Clock[IO].timestamp
      _ <- transaction(insertVideo("video-1", 5 minutes, timestamp))
      _ <- transaction(insertVideo("video-2", 5 minutes, timestamp))
      _ <- transaction(insertVideo("video-3", 10 minutes, timestamp))

      hash1 = VideoPerceptualHash("video-1", timestamp, 5 minutes, BigInt(111L), 150 seconds)
      hash2 = VideoPerceptualHash("video-2", timestamp, 5 minutes, BigInt(222L), 150 seconds)
      hash3 = VideoPerceptualHash("video-3", timestamp, 10 minutes, BigInt(333L), 300 seconds)

      _ <- transaction {
        DoobieVideoPerceptualHashDao.insert(hash1) *>
          DoobieVideoPerceptualHashDao.insert(hash2) *>
          DoobieVideoPerceptualHashDao.insert(hash3)
      }

      fiveMinHashes <- transaction(DoobieVideoPerceptualHashDao.findVideoHashesByDuration(5 minutes))
      _ <- IO.delay {
        fiveMinHashes.size mustBe 2
        fiveMinHashes.map(_.videoId).toSet mustBe Set("video-1", "video-2")
      }

      tenMinHashes <- transaction(DoobieVideoPerceptualHashDao.findVideoHashesByDuration(10 minutes))
      _ <- IO.delay {
        tenMinHashes.size mustBe 1
        tenMinHashes.head.videoId mustBe "video-3"
      }

      noHashes <- transaction(DoobieVideoPerceptualHashDao.findVideoHashesByDuration(15 minutes))
      _ <- IO.delay { noHashes mustBe empty }
    } yield ()
  }

  it should "return empty list when getting hashes for a video without hashes" in runTest { transaction =>
    for {
      timestamp <- Clock[IO].timestamp
      _ <- transaction(insertVideo("video-1", 5 minutes, timestamp))

      hashes <- transaction(DoobieVideoPerceptualHashDao.getByVideoId("video-1"))
      _ <- IO.delay { hashes mustBe empty }
    } yield ()
  }

  it should "return empty list for a non-existent video" in runTest { transaction =>
    for {
      hashes <- transaction(DoobieVideoPerceptualHashDao.getByVideoId("non-existent"))
      _ <- IO.delay { hashes mustBe empty }
    } yield ()
  }

  it should "support multiple hashes for the same video" in runTest { transaction =>
    for {
      timestamp <- Clock[IO].timestamp
      _ <- transaction(insertVideo("video-1", 5 minutes, timestamp))

      hash1 = VideoPerceptualHash("video-1", timestamp, 5 minutes, BigInt(111L), 60 seconds)
      hash2 = VideoPerceptualHash("video-1", timestamp, 5 minutes, BigInt(222L), 120 seconds)

      _ <- transaction {
        DoobieVideoPerceptualHashDao.insert(hash1) *>
          DoobieVideoPerceptualHashDao.insert(hash2)
      }

      hashes <- transaction(DoobieVideoPerceptualHashDao.getByVideoId("video-1"))
      _ <- IO.delay {
        hashes.size mustBe 2
        hashes.map(_.snapshotTimestamp).toSet mustBe Set(60 seconds, 120 seconds)
      }
    } yield ()
  }
}
