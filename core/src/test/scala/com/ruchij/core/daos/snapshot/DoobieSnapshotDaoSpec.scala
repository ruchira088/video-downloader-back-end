package com.ruchij.core.daos.snapshot

import cats.effect.IO
import cats.~>
import com.ruchij.core.daos.permission.DoobieVideoPermissionDao
import com.ruchij.core.daos.permission.models.VideoPermission
import com.ruchij.core.daos.resource.DoobieFileResourceDao
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.scheduling.models.SchedulingStatus
import com.ruchij.core.daos.snapshot.models.Snapshot
import com.ruchij.core.daos.video.DoobieVideoDao
import com.ruchij.core.daos.videometadata.DoobieVideoMetadataDao
import com.ruchij.core.daos.videometadata.models.{CustomVideoSite, VideoMetadata}
import com.ruchij.core.external.embedded.EmbeddedCoreResourcesProvider
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.types.Clock
import doobie.ConnectionIO
import doobie.implicits._
import org.http4s.MediaType
import org.http4s.implicits.http4sLiteralsSyntax
import java.time.Instant
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps

class DoobieSnapshotDaoSpec extends AnyFlatSpec with Matchers with OptionValues {

  import com.ruchij.core.daos.doobie.DoobieCustomMappings._

  case class TestFixture(
    snapshot: Snapshot,
    videoId: String,
    snapshotFileResource: FileResource,
    videoMetadata: VideoMetadata,
    transaction: ConnectionIO ~> IO
  )

  private def insertTestUser(userId: String, email: String, timestamp: Instant): ConnectionIO[Int] =
    sql"""
      INSERT INTO api_user (id, created_at, first_name, last_name, email, role)
        VALUES ($userId, $timestamp, 'Test', 'User', $email, 'User')
    """.update.run

  private def insertScheduledVideo(videoId: String, timestamp: Instant): ConnectionIO[Int] =
    sql"""
      INSERT INTO scheduled_video (scheduled_at, last_updated_at, status, downloaded_bytes, video_metadata_id, completed_at)
        VALUES ($timestamp, $timestamp, ${SchedulingStatus.Completed: SchedulingStatus}, 0, $videoId, $timestamp)
    """.update.run

  def runTest(testFn: TestFixture => IO[Unit]): Unit =
    runIO {
      new EmbeddedCoreResourcesProvider[IO].transactor.use { transaction =>
        for {
          timestamp <- Clock[IO].timestamp
          videoId = "test-video-id"

          // Create thumbnail file resource for video metadata
          thumbnailFileResource = FileResource(
            "thumbnail-id",
            timestamp,
            "/opt/image/thumbnail.jpg",
            MediaType.image.jpeg,
            1000
          )
          _ <- transaction(DoobieFileResourceDao.insert(thumbnailFileResource))

          // Create video metadata
          videoMetadata = VideoMetadata(
            uri"https://example.com/video",
            videoId,
            CustomVideoSite.SpankBang,
            "Test Video Title",
            5 minutes,
            50000,
            thumbnailFileResource
          )
          _ <- transaction(DoobieVideoMetadataDao.insert(videoMetadata))

          // Create video file resource
          videoFileResource = FileResource(
            "video-file-id",
            timestamp,
            "/opt/video/test-video.mp4",
            MediaType.video.mp4,
            50000
          )
          _ <- transaction(DoobieFileResourceDao.insert(videoFileResource))

          // Insert video
          _ <- transaction(DoobieVideoDao.insert(videoId, videoFileResource.id, timestamp, 0 seconds))

          // Create snapshot file resource
          snapshotFileResource = FileResource(
            "snapshot-file-id",
            timestamp,
            "/opt/snapshots/snapshot-001.jpg",
            MediaType.image.jpeg,
            5000
          )
          _ <- transaction(DoobieFileResourceDao.insert(snapshotFileResource))

          // Create snapshot
          snapshot = Snapshot(
            videoId,
            snapshotFileResource,
            30 seconds
          )

          result <- testFn(TestFixture(snapshot, videoId, snapshotFileResource, videoMetadata, transaction))
        } yield result
      }
    }

  "DoobieSnapshotDao" should "insert and retrieve a snapshot by video ID" in runTest { fixture =>
    for {
      insertResult <- fixture.transaction(DoobieSnapshotDao.insert(fixture.snapshot))
      _ <- IO.delay { insertResult mustBe 1 }

      snapshots <- fixture.transaction(DoobieSnapshotDao.findByVideo(fixture.videoId, None))

      _ <- IO.delay {
        snapshots.size mustBe 1
        snapshots.head.videoId mustBe fixture.videoId
        snapshots.head.fileResource.id mustBe fixture.snapshotFileResource.id
        snapshots.head.fileResource.path mustBe fixture.snapshotFileResource.path
        snapshots.head.fileResource.mediaType mustBe fixture.snapshotFileResource.mediaType
        snapshots.head.fileResource.size mustBe fixture.snapshotFileResource.size
        snapshots.head.videoTimestamp mustBe (30 seconds)
      }
    } yield ()
  }

  it should "insert multiple snapshots for the same video" in runTest { fixture =>
    for {
      timestamp <- Clock[IO].timestamp

      // Create second snapshot file resource
      snapshotFileResource2 = FileResource(
        "snapshot-file-id-2",
        timestamp,
        "/opt/snapshots/snapshot-002.jpg",
        MediaType.image.jpeg,
        6000
      )
      _ <- fixture.transaction(DoobieFileResourceDao.insert(snapshotFileResource2))

      // Create third snapshot file resource
      snapshotFileResource3 = FileResource(
        "snapshot-file-id-3",
        timestamp,
        "/opt/snapshots/snapshot-003.jpg",
        MediaType.image.jpeg,
        7000
      )
      _ <- fixture.transaction(DoobieFileResourceDao.insert(snapshotFileResource3))

      snapshot1 = fixture.snapshot
      snapshot2 = Snapshot(fixture.videoId, snapshotFileResource2, 1 minute)
      snapshot3 = Snapshot(fixture.videoId, snapshotFileResource3, 2 minutes)

      _ <- fixture.transaction(DoobieSnapshotDao.insert(snapshot1))
      _ <- fixture.transaction(DoobieSnapshotDao.insert(snapshot2))
      _ <- fixture.transaction(DoobieSnapshotDao.insert(snapshot3))

      snapshots <- fixture.transaction(DoobieSnapshotDao.findByVideo(fixture.videoId, None))

      _ <- IO.delay {
        snapshots.size mustBe 3
        snapshots.map(_.videoTimestamp).toSet mustBe Set(30 seconds, 1 minute, 2 minutes)
        snapshots.map(_.fileResource.id).toSet mustBe Set(
          "snapshot-file-id",
          "snapshot-file-id-2",
          "snapshot-file-id-3"
        )
      }
    } yield ()
  }

  it should "return empty sequence when no snapshots exist for video" in runTest { fixture =>
    for {
      snapshots <- fixture.transaction(DoobieSnapshotDao.findByVideo(fixture.videoId, None))

      _ <- IO.delay {
        snapshots mustBe empty
      }
    } yield ()
  }

  it should "return empty sequence for non-existent video ID" in runTest { fixture =>
    for {
      _ <- fixture.transaction(DoobieSnapshotDao.insert(fixture.snapshot))

      snapshots <- fixture.transaction(DoobieSnapshotDao.findByVideo("non-existent-video-id", None))

      _ <- IO.delay {
        snapshots mustBe empty
      }
    } yield ()
  }

  it should "delete all snapshots by video ID" in runTest { fixture =>
    for {
      timestamp <- Clock[IO].timestamp

      // Create second snapshot file resource
      snapshotFileResource2 = FileResource(
        "snapshot-file-id-2",
        timestamp,
        "/opt/snapshots/snapshot-002.jpg",
        MediaType.image.jpeg,
        6000
      )
      _ <- fixture.transaction(DoobieFileResourceDao.insert(snapshotFileResource2))

      snapshot1 = fixture.snapshot
      snapshot2 = Snapshot(fixture.videoId, snapshotFileResource2, 1 minute)

      _ <- fixture.transaction(DoobieSnapshotDao.insert(snapshot1))
      _ <- fixture.transaction(DoobieSnapshotDao.insert(snapshot2))

      snapshotsBefore <- fixture.transaction(DoobieSnapshotDao.findByVideo(fixture.videoId, None))
      _ <- IO.delay { snapshotsBefore.size mustBe 2 }

      deleteCount <- fixture.transaction(DoobieSnapshotDao.deleteByVideo(fixture.videoId))
      _ <- IO.delay { deleteCount mustBe 2 }

      snapshotsAfter <- fixture.transaction(DoobieSnapshotDao.findByVideo(fixture.videoId, None))
      _ <- IO.delay { snapshotsAfter mustBe empty }
    } yield ()
  }

  it should "return 0 when deleting snapshots for video with no snapshots" in runTest { fixture =>
    for {
      deleteCount <- fixture.transaction(DoobieSnapshotDao.deleteByVideo(fixture.videoId))

      _ <- IO.delay {
        deleteCount mustBe 0
      }
    } yield ()
  }

  it should "return 0 when deleting snapshots for non-existent video" in runTest { fixture =>
    for {
      _ <- fixture.transaction(DoobieSnapshotDao.insert(fixture.snapshot))

      deleteCount <- fixture.transaction(DoobieSnapshotDao.deleteByVideo("non-existent-video-id"))

      _ <- IO.delay {
        deleteCount mustBe 0
      }

      // Verify original snapshot still exists
      snapshots <- fixture.transaction(DoobieSnapshotDao.findByVideo(fixture.videoId, None))
      _ <- IO.delay { snapshots.size mustBe 1 }
    } yield ()
  }

  it should "check hasPermission returns false when no permission exists" in runTest { fixture =>
    for {
      timestamp <- Clock[IO].timestamp
      userId = "test-user-id"

      _ <- fixture.transaction(insertTestUser(userId, "test@example.com", timestamp))
      _ <- fixture.transaction(insertScheduledVideo(fixture.videoId, timestamp))
      _ <- fixture.transaction(DoobieSnapshotDao.insert(fixture.snapshot))

      hasPermission <- fixture.transaction(
        DoobieSnapshotDao.hasPermission(fixture.snapshotFileResource.id, userId)
      )

      _ <- IO.delay {
        hasPermission mustBe false
      }
    } yield ()
  }

  it should "check hasPermission returns true when permission exists" in runTest { fixture =>
    for {
      timestamp <- Clock[IO].timestamp
      userId = "test-user-id"

      _ <- fixture.transaction(insertTestUser(userId, "test@example.com", timestamp))
      _ <- fixture.transaction(insertScheduledVideo(fixture.videoId, timestamp))
      _ <- fixture.transaction(DoobieSnapshotDao.insert(fixture.snapshot))

      // Grant permission
      _ <- fixture.transaction(
        DoobieVideoPermissionDao.insert(VideoPermission(timestamp, fixture.videoId, userId))
      )

      hasPermission <- fixture.transaction(
        DoobieSnapshotDao.hasPermission(fixture.snapshotFileResource.id, userId)
      )

      _ <- IO.delay {
        hasPermission mustBe true
      }
    } yield ()
  }

  it should "check hasPermission returns false for different user" in runTest { fixture =>
    for {
      timestamp <- Clock[IO].timestamp
      userId1 = "test-user-id-1"
      userId2 = "test-user-id-2"

      _ <- fixture.transaction(insertTestUser(userId1, "test1@example.com", timestamp))
      _ <- fixture.transaction(insertTestUser(userId2, "test2@example.com", timestamp))
      _ <- fixture.transaction(insertScheduledVideo(fixture.videoId, timestamp))
      _ <- fixture.transaction(DoobieSnapshotDao.insert(fixture.snapshot))

      // Grant permission only to user1
      _ <- fixture.transaction(
        DoobieVideoPermissionDao.insert(VideoPermission(timestamp, fixture.videoId, userId1))
      )

      hasPermissionUser1 <- fixture.transaction(
        DoobieSnapshotDao.hasPermission(fixture.snapshotFileResource.id, userId1)
      )

      hasPermissionUser2 <- fixture.transaction(
        DoobieSnapshotDao.hasPermission(fixture.snapshotFileResource.id, userId2)
      )

      _ <- IO.delay {
        hasPermissionUser1 mustBe true
        hasPermissionUser2 mustBe false
      }
    } yield ()
  }

  it should "check hasPermission returns false for non-existent snapshot" in runTest { fixture =>
    for {
      timestamp <- Clock[IO].timestamp
      userId = "test-user-id"

      _ <- fixture.transaction(insertTestUser(userId, "test@example.com", timestamp))
      _ <- fixture.transaction(insertScheduledVideo(fixture.videoId, timestamp))
      _ <- fixture.transaction(
        DoobieVideoPermissionDao.insert(VideoPermission(timestamp, fixture.videoId, userId))
      )

      hasPermission <- fixture.transaction(
        DoobieSnapshotDao.hasPermission("non-existent-snapshot-id", userId)
      )

      _ <- IO.delay {
        hasPermission mustBe false
      }
    } yield ()
  }

  it should "find snapshots by video with user permission" in runTest { fixture =>
    for {
      timestamp <- Clock[IO].timestamp
      userId = "test-user-id"

      _ <- fixture.transaction(insertTestUser(userId, "test@example.com", timestamp))
      _ <- fixture.transaction(insertScheduledVideo(fixture.videoId, timestamp))
      _ <- fixture.transaction(DoobieSnapshotDao.insert(fixture.snapshot))

      // Query without permission should return empty
      snapshotsWithoutPermission <- fixture.transaction(
        DoobieSnapshotDao.findByVideo(fixture.videoId, Some(userId))
      )
      _ <- IO.delay { snapshotsWithoutPermission mustBe empty }

      // Grant permission
      _ <- fixture.transaction(
        DoobieVideoPermissionDao.insert(VideoPermission(timestamp, fixture.videoId, userId))
      )

      // Query with permission should return snapshot
      snapshotsWithPermission <- fixture.transaction(
        DoobieSnapshotDao.findByVideo(fixture.videoId, Some(userId))
      )

      _ <- IO.delay {
        snapshotsWithPermission.size mustBe 1
        snapshotsWithPermission.head.videoId mustBe fixture.videoId
        snapshotsWithPermission.head.fileResource.id mustBe fixture.snapshotFileResource.id
      }
    } yield ()
  }

  it should "find snapshots by video with user permission for different user returns empty" in runTest { fixture =>
    for {
      timestamp <- Clock[IO].timestamp
      userId1 = "test-user-id-1"
      userId2 = "test-user-id-2"

      _ <- fixture.transaction(insertTestUser(userId1, "test1@example.com", timestamp))
      _ <- fixture.transaction(insertTestUser(userId2, "test2@example.com", timestamp))
      _ <- fixture.transaction(insertScheduledVideo(fixture.videoId, timestamp))
      _ <- fixture.transaction(DoobieSnapshotDao.insert(fixture.snapshot))

      // Grant permission only to user1
      _ <- fixture.transaction(
        DoobieVideoPermissionDao.insert(VideoPermission(timestamp, fixture.videoId, userId1))
      )

      snapshotsUser1 <- fixture.transaction(
        DoobieSnapshotDao.findByVideo(fixture.videoId, Some(userId1))
      )

      snapshotsUser2 <- fixture.transaction(
        DoobieSnapshotDao.findByVideo(fixture.videoId, Some(userId2))
      )

      _ <- IO.delay {
        snapshotsUser1.size mustBe 1
        snapshotsUser2 mustBe empty
      }
    } yield ()
  }

  it should "preserve file resource details when retrieving snapshots" in runTest { fixture =>
    for {
      _ <- fixture.transaction(DoobieSnapshotDao.insert(fixture.snapshot))

      snapshots <- fixture.transaction(DoobieSnapshotDao.findByVideo(fixture.videoId, None))

      _ <- IO.delay {
        snapshots.size mustBe 1
        val retrievedSnapshot = snapshots.head
        retrievedSnapshot.fileResource.id mustBe fixture.snapshotFileResource.id
        retrievedSnapshot.fileResource.createdAt.toEpochMilli mustBe fixture.snapshotFileResource.createdAt.toEpochMilli
        retrievedSnapshot.fileResource.path mustBe fixture.snapshotFileResource.path
        retrievedSnapshot.fileResource.mediaType mustBe fixture.snapshotFileResource.mediaType
        retrievedSnapshot.fileResource.size mustBe fixture.snapshotFileResource.size
      }
    } yield ()
  }

  it should "handle snapshots with zero video timestamp" in runTest { fixture =>
    val snapshotAtStart = fixture.snapshot.copy(videoTimestamp = 0 seconds)

    for {
      insertResult <- fixture.transaction(DoobieSnapshotDao.insert(snapshotAtStart))
      _ <- IO.delay { insertResult mustBe 1 }

      snapshots <- fixture.transaction(DoobieSnapshotDao.findByVideo(fixture.videoId, None))

      _ <- IO.delay {
        snapshots.size mustBe 1
        snapshots.head.videoTimestamp mustBe (0 seconds)
      }
    } yield ()
  }

  it should "handle snapshots with large video timestamp" in runTest { fixture =>
    val longVideoTimestamp = 3 hours
    val snapshotAtEnd = fixture.snapshot.copy(videoTimestamp = longVideoTimestamp)

    for {
      insertResult <- fixture.transaction(DoobieSnapshotDao.insert(snapshotAtEnd))
      _ <- IO.delay { insertResult mustBe 1 }

      snapshots <- fixture.transaction(DoobieSnapshotDao.findByVideo(fixture.videoId, None))

      _ <- IO.delay {
        snapshots.size mustBe 1
        snapshots.head.videoTimestamp mustBe longVideoTimestamp
      }
    } yield ()
  }

  it should "correctly isolate snapshots between different videos" in runTest { fixture =>
    for {
      timestamp <- Clock[IO].timestamp

      // Create second video with its own metadata and file resources
      thumbnailFileResource2 = FileResource(
        "thumbnail-id-2",
        timestamp,
        "/opt/image/thumbnail2.jpg",
        MediaType.image.jpeg,
        1000
      )
      _ <- fixture.transaction(DoobieFileResourceDao.insert(thumbnailFileResource2))

      videoMetadata2 = VideoMetadata(
        uri"https://example.com/video2",
        "test-video-id-2",
        CustomVideoSite.SpankBang,
        "Test Video Title 2",
        10 minutes,
        100000,
        thumbnailFileResource2
      )
      _ <- fixture.transaction(DoobieVideoMetadataDao.insert(videoMetadata2))

      videoFileResource2 = FileResource(
        "video-file-id-2",
        timestamp,
        "/opt/video/test-video2.mp4",
        MediaType.video.mp4,
        100000
      )
      _ <- fixture.transaction(DoobieFileResourceDao.insert(videoFileResource2))
      _ <- fixture.transaction(DoobieVideoDao.insert("test-video-id-2", videoFileResource2.id, timestamp, 0 seconds))

      // Create snapshot file resources for second video
      snapshotFileResourceForVideo2 = FileResource(
        "snapshot-file-id-video2",
        timestamp,
        "/opt/snapshots/snapshot-video2.jpg",
        MediaType.image.jpeg,
        5000
      )
      _ <- fixture.transaction(DoobieFileResourceDao.insert(snapshotFileResourceForVideo2))

      // Insert snapshots for both videos
      _ <- fixture.transaction(DoobieSnapshotDao.insert(fixture.snapshot))
      snapshotForVideo2 = Snapshot("test-video-id-2", snapshotFileResourceForVideo2, 45 seconds)
      _ <- fixture.transaction(DoobieSnapshotDao.insert(snapshotForVideo2))

      // Verify each video has only its own snapshot
      snapshotsVideo1 <- fixture.transaction(DoobieSnapshotDao.findByVideo(fixture.videoId, None))
      snapshotsVideo2 <- fixture.transaction(DoobieSnapshotDao.findByVideo("test-video-id-2", None))

      _ <- IO.delay {
        snapshotsVideo1.size mustBe 1
        snapshotsVideo1.head.videoId mustBe fixture.videoId
        snapshotsVideo1.head.fileResource.id mustBe "snapshot-file-id"

        snapshotsVideo2.size mustBe 1
        snapshotsVideo2.head.videoId mustBe "test-video-id-2"
        snapshotsVideo2.head.fileResource.id mustBe "snapshot-file-id-video2"
      }

      // Delete snapshots for video1 only
      deleteCount <- fixture.transaction(DoobieSnapshotDao.deleteByVideo(fixture.videoId))
      _ <- IO.delay { deleteCount mustBe 1 }

      // Verify video2 snapshots are still there
      snapshotsVideo1After <- fixture.transaction(DoobieSnapshotDao.findByVideo(fixture.videoId, None))
      snapshotsVideo2After <- fixture.transaction(DoobieSnapshotDao.findByVideo("test-video-id-2", None))

      _ <- IO.delay {
        snapshotsVideo1After mustBe empty
        snapshotsVideo2After.size mustBe 1
      }
    } yield ()
  }

  it should "handle hasPermission with multiple snapshots for the same video" in runTest { fixture =>
    for {
      timestamp <- Clock[IO].timestamp
      userId = "test-user-id"

      // Create second snapshot file resource
      snapshotFileResource2 = FileResource(
        "snapshot-file-id-2",
        timestamp,
        "/opt/snapshots/snapshot-002.jpg",
        MediaType.image.jpeg,
        6000
      )
      _ <- fixture.transaction(DoobieFileResourceDao.insert(snapshotFileResource2))

      snapshot2 = Snapshot(fixture.videoId, snapshotFileResource2, 1 minute)

      _ <- fixture.transaction(DoobieSnapshotDao.insert(fixture.snapshot))
      _ <- fixture.transaction(DoobieSnapshotDao.insert(snapshot2))

      _ <- fixture.transaction(insertTestUser(userId, "test@example.com", timestamp))
      _ <- fixture.transaction(insertScheduledVideo(fixture.videoId, timestamp))

      // Check permissions before granting
      hasPermission1Before <- fixture.transaction(
        DoobieSnapshotDao.hasPermission(fixture.snapshotFileResource.id, userId)
      )
      hasPermission2Before <- fixture.transaction(
        DoobieSnapshotDao.hasPermission(snapshotFileResource2.id, userId)
      )

      _ <- IO.delay {
        hasPermission1Before mustBe false
        hasPermission2Before mustBe false
      }

      // Grant permission for the video
      _ <- fixture.transaction(
        DoobieVideoPermissionDao.insert(VideoPermission(timestamp, fixture.videoId, userId))
      )

      // Check permissions after granting - both snapshots should be accessible
      hasPermission1After <- fixture.transaction(
        DoobieSnapshotDao.hasPermission(fixture.snapshotFileResource.id, userId)
      )
      hasPermission2After <- fixture.transaction(
        DoobieSnapshotDao.hasPermission(snapshotFileResource2.id, userId)
      )

      _ <- IO.delay {
        hasPermission1After mustBe true
        hasPermission2After mustBe true
      }
    } yield ()
  }

  it should "correctly return insert count of 1 for single snapshot" in runTest { fixture =>
    for {
      insertResult <- fixture.transaction(DoobieSnapshotDao.insert(fixture.snapshot))

      _ <- IO.delay {
        insertResult mustBe 1
      }
    } yield ()
  }

  it should "handle video timestamps with millisecond precision" in runTest { fixture =>
    val preciseTimestamp = 123456.milliseconds
    val snapshotWithPrecision = fixture.snapshot.copy(videoTimestamp = preciseTimestamp)

    for {
      _ <- fixture.transaction(DoobieSnapshotDao.insert(snapshotWithPrecision))

      snapshots <- fixture.transaction(DoobieSnapshotDao.findByVideo(fixture.videoId, None))

      _ <- IO.delay {
        snapshots.size mustBe 1
        snapshots.head.videoTimestamp mustBe preciseTimestamp
      }
    } yield ()
  }

  it should "not find snapshots for video when querying without permission for a user that exists" in runTest { fixture =>
    for {
      timestamp <- Clock[IO].timestamp
      userId = "test-user-id"

      _ <- fixture.transaction(insertTestUser(userId, "test@example.com", timestamp))
      _ <- fixture.transaction(insertScheduledVideo(fixture.videoId, timestamp))
      _ <- fixture.transaction(DoobieSnapshotDao.insert(fixture.snapshot))

      // Query without permission
      snapshotsWithUserId <- fixture.transaction(
        DoobieSnapshotDao.findByVideo(fixture.videoId, Some(userId))
      )

      // Query without user ID should still return results
      snapshotsWithoutUserId <- fixture.transaction(
        DoobieSnapshotDao.findByVideo(fixture.videoId, None)
      )

      _ <- IO.delay {
        snapshotsWithUserId mustBe empty
        snapshotsWithoutUserId.size mustBe 1
      }
    } yield ()
  }

  it should "return true for isSnapshotFileResource when file resource is a snapshot" in runTest { fixture =>
    for {
      _ <- fixture.transaction(DoobieSnapshotDao.insert(fixture.snapshot))

      isSnapshot <- fixture.transaction(
        DoobieSnapshotDao.isSnapshotFileResource(fixture.snapshotFileResource.id)
      )

      _ <- IO.delay {
        isSnapshot mustBe true
      }
    } yield ()
  }

  it should "return false for isSnapshotFileResource when file resource is not a snapshot" in runTest { fixture =>
    for {
      isSnapshot <- fixture.transaction(
        DoobieSnapshotDao.isSnapshotFileResource("non-existent-file-resource")
      )

      _ <- IO.delay {
        isSnapshot mustBe false
      }
    } yield ()
  }

  it should "return false for isSnapshotFileResource when file resource exists but is not a snapshot" in runTest { fixture =>
    for {
      // thumbnail-id exists as a file resource but is not a snapshot
      isSnapshot <- fixture.transaction(
        DoobieSnapshotDao.isSnapshotFileResource("thumbnail-id")
      )

      _ <- IO.delay {
        isSnapshot mustBe false
      }
    } yield ()
  }
}
