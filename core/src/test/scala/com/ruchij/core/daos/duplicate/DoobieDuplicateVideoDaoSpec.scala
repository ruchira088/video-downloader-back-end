package com.ruchij.core.daos.duplicate

import cats.effect.IO
import cats.implicits._
import cats.~>
import com.ruchij.core.daos.duplicate.models.DuplicateVideo
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
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps

class DoobieDuplicateVideoDaoSpec extends AnyFlatSpec with Matchers with OptionValues {

  private def insertVideo(videoId: String, timestamp: java.time.Instant): ConnectionIO[Unit] = {
    val thumbnailId = s"$videoId-thumb"
    val thumbnail = FileResource(thumbnailId, timestamp, s"/opt/images/$thumbnailId.jpg", MediaType.image.jpeg, 1000)
    val videoMetadata = VideoMetadata(
      org.http4s.Uri.unsafeFromString(s"https://spankbang.com/video/$videoId"),
      videoId,
      CustomVideoSite.SpankBang,
      s"Video $videoId",
      5 minutes,
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

  "DoobieDuplicateVideoDao" should "insert and retrieve a duplicate video by videoId" in runTest { transaction =>
    for {
      timestamp <- Clock[IO].timestamp
      _ <- transaction(insertVideo("video-1", timestamp))

      duplicateVideo = DuplicateVideo("video-1", "group-1", timestamp)
      insertResult <- transaction(DoobieDuplicateVideoDao.insert(duplicateVideo))
      _ <- IO.delay { insertResult mustBe 1 }

      result <- transaction(DoobieDuplicateVideoDao.findByVideoId("video-1"))
      _ <- IO.delay {
        result.value.videoId mustBe "video-1"
        result.value.duplicateGroupId mustBe "group-1"
        result.value.createdAt.toEpochMilli mustBe timestamp.toEpochMilli
      }
    } yield ()
  }

  it should "return None when finding a non-existent videoId" in runTest { transaction =>
    for {
      result <- transaction(DoobieDuplicateVideoDao.findByVideoId("non-existent"))
      _ <- IO.delay { result mustBe None }
    } yield ()
  }

  it should "delete a duplicate video record" in runTest { transaction =>
    for {
      timestamp <- Clock[IO].timestamp
      _ <- transaction(insertVideo("video-1", timestamp))

      duplicateVideo = DuplicateVideo("video-1", "group-1", timestamp)
      _ <- transaction(DoobieDuplicateVideoDao.insert(duplicateVideo))

      deleteResult <- transaction(DoobieDuplicateVideoDao.delete("video-1"))
      _ <- IO.delay { deleteResult mustBe 1 }

      result <- transaction(DoobieDuplicateVideoDao.findByVideoId("video-1"))
      _ <- IO.delay { result mustBe None }
    } yield ()
  }

  it should "return 0 when deleting a non-existent videoId" in runTest { transaction =>
    for {
      deleteResult <- transaction(DoobieDuplicateVideoDao.delete("non-existent"))
      _ <- IO.delay { deleteResult mustBe 0 }
    } yield ()
  }

  it should "find all videos by duplicate group ID" in runTest { transaction =>
    for {
      timestamp <- Clock[IO].timestamp
      _ <- transaction(insertVideo("video-1", timestamp))
      _ <- transaction(insertVideo("video-2", timestamp))
      _ <- transaction(insertVideo("video-3", timestamp))

      _ <- transaction {
        DoobieDuplicateVideoDao.insert(DuplicateVideo("video-1", "group-a", timestamp)) *>
          DoobieDuplicateVideoDao.insert(DuplicateVideo("video-2", "group-a", timestamp)) *>
          DoobieDuplicateVideoDao.insert(DuplicateVideo("video-3", "group-b", timestamp))
      }

      groupA <- transaction(DoobieDuplicateVideoDao.findByDuplicateGroupId("group-a"))
      _ <- IO.delay {
        groupA.map(_.videoId).toSet mustBe Set("video-1", "video-2")
      }

      groupB <- transaction(DoobieDuplicateVideoDao.findByDuplicateGroupId("group-b"))
      _ <- IO.delay {
        groupB.size mustBe 1
        groupB.head.videoId mustBe "video-3"
      }

      groupNone <- transaction(DoobieDuplicateVideoDao.findByDuplicateGroupId("non-existent"))
      _ <- IO.delay { groupNone mustBe empty }
    } yield ()
  }

  it should "get all duplicate videos with group-level pagination" in runTest { transaction =>
    for {
      timestamp <- Clock[IO].timestamp
      _ <- transaction(insertVideo("video-1", timestamp))
      _ <- transaction(insertVideo("video-2", timestamp))
      _ <- transaction(insertVideo("video-3", timestamp))

      _ <- transaction {
        DoobieDuplicateVideoDao.insert(DuplicateVideo("video-1", "group-a", timestamp)) *>
          DoobieDuplicateVideoDao.insert(DuplicateVideo("video-2", "group-a", timestamp)) *>
          DoobieDuplicateVideoDao.insert(DuplicateVideo("video-3", "group-b", timestamp))
      }

      allResults <- transaction(DoobieDuplicateVideoDao.getAll(offset = 0, limit = 10))
      _ <- IO.delay { allResults.size mustBe 3 }

      firstGroup <- transaction(DoobieDuplicateVideoDao.getAll(offset = 0, limit = 1))
      _ <- IO.delay {
        firstGroup.map(_.duplicateGroupId).toSet.size mustBe 1
        firstGroup.size mustBe 2
      }

      secondGroup <- transaction(DoobieDuplicateVideoDao.getAll(offset = 1, limit = 1))
      _ <- IO.delay {
        secondGroup.size mustBe 1
        secondGroup.head.duplicateGroupId mustBe "group-b"
      }

      emptyPage <- transaction(DoobieDuplicateVideoDao.getAll(offset = 10, limit = 10))
      _ <- IO.delay { emptyPage mustBe empty }
    } yield ()
  }

  it should "return distinct duplicate group IDs" in runTest { transaction =>
    for {
      timestamp <- Clock[IO].timestamp
      _ <- transaction(insertVideo("video-1", timestamp))
      _ <- transaction(insertVideo("video-2", timestamp))
      _ <- transaction(insertVideo("video-3", timestamp))

      _ <- transaction {
        DoobieDuplicateVideoDao.insert(DuplicateVideo("video-1", "group-a", timestamp)) *>
          DoobieDuplicateVideoDao.insert(DuplicateVideo("video-2", "group-a", timestamp)) *>
          DoobieDuplicateVideoDao.insert(DuplicateVideo("video-3", "group-b", timestamp))
      }

      groupIds <- transaction(DoobieDuplicateVideoDao.duplicateGroupIds)
      _ <- IO.delay {
        groupIds.toSet mustBe Set("group-a", "group-b")
      }
    } yield ()
  }

  it should "return empty group IDs when no duplicates exist" in runTest { transaction =>
    for {
      groupIds <- transaction(DoobieDuplicateVideoDao.duplicateGroupIds)
      _ <- IO.delay { groupIds mustBe empty }
    } yield ()
  }
}
