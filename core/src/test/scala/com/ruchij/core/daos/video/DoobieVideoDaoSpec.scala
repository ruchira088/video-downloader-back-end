package com.ruchij.core.daos.video

import cats.data.NonEmptyList
import cats.effect.IO
import cats.~>
import com.ruchij.core.daos.permission.DoobieVideoPermissionDao
import com.ruchij.core.daos.permission.models.VideoPermission
import com.ruchij.core.daos.resource.DoobieFileResourceDao
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.scheduling.models.{RangeValue, SchedulingStatus}
import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.daos.videometadata.DoobieVideoMetadataDao
import com.ruchij.core.daos.videometadata.models.{CustomVideoSite, VideoMetadata, VideoSite}
import com.ruchij.core.external.embedded.EmbeddedCoreResourcesProvider
import com.ruchij.core.services.models.{Order, SortBy}
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

class DoobieVideoDaoSpec extends AnyFlatSpec with Matchers with OptionValues {

  import com.ruchij.core.daos.doobie.DoobieCustomMappings._

  private def insertTestUser(userId: String, email: String, timestamp: Instant): ConnectionIO[Int] =
    sql"""
      INSERT INTO api_user (id, created_at, first_name, last_name, email, role)
        VALUES ($userId, $timestamp, 'Test', 'User', $email, 'User')
    """.update.run

  case class TestFixture(
    video: Video,
    videoMetadata: VideoMetadata,
    videoFileResource: FileResource,
    thumbnailFileResource: FileResource,
    transaction: ConnectionIO ~> IO
  )

  def runTest(testFn: TestFixture => IO[Unit]): Unit =
    runIO {
      new EmbeddedCoreResourcesProvider[IO].transactor.use { transaction =>
        for {
          timestamp <- Clock[IO].timestamp
          videoId = "test-video-id"

          thumbnailFileResource = FileResource(
            "thumbnail-id",
            timestamp,
            "/opt/image/thumbnail.jpg",
            MediaType.image.jpeg,
            1000
          )
          _ <- transaction(DoobieFileResourceDao.insert(thumbnailFileResource))

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

          videoFileResource = FileResource(
            "video-file-id",
            timestamp,
            "/opt/video/test-video.mp4",
            MediaType.video.mp4,
            50000
          )
          _ <- transaction(DoobieFileResourceDao.insert(videoFileResource))

          insertResult <- transaction(DoobieVideoDao.insert(videoId, videoFileResource.id, timestamp, 0 seconds))
          _ <- IO.delay { insertResult mustBe 2 }

          video = Video(videoMetadata, videoFileResource, timestamp, 0 seconds)

          result <- testFn(TestFixture(video, videoMetadata, videoFileResource, thumbnailFileResource, transaction))
        } yield result
      }
    }

  "DoobieVideoDao" should "insert and retrieve a video" in runTest { fixture =>
    for {
      maybeVideo <- fixture.transaction(DoobieVideoDao.findById(fixture.video.videoMetadata.id, None))

      _ <- IO.delay {
        maybeVideo mustBe defined
        maybeVideo.value.videoMetadata.id mustBe fixture.videoMetadata.id
        maybeVideo.value.videoMetadata.title mustBe fixture.videoMetadata.title
        maybeVideo.value.videoMetadata.duration mustBe fixture.videoMetadata.duration
        maybeVideo.value.videoMetadata.size mustBe fixture.videoMetadata.size
        maybeVideo.value.videoMetadata.videoSite mustBe fixture.videoMetadata.videoSite
        maybeVideo.value.fileResource.id mustBe fixture.videoFileResource.id
        maybeVideo.value.fileResource.path mustBe fixture.videoFileResource.path
        maybeVideo.value.watchTime mustBe (0 seconds)
      }
    } yield ()
  }

  it should "find video by video file resource ID" in runTest { fixture =>
    for {
      maybeVideo <- fixture.transaction(
        DoobieVideoDao.findByVideoFileResourceId(fixture.videoFileResource.id)
      )

      _ <- IO.delay {
        maybeVideo mustBe defined
        maybeVideo.value.videoMetadata.id mustBe fixture.videoMetadata.id
      }
    } yield ()
  }

  it should "find video by video path" in runTest { fixture =>
    for {
      maybeVideo <- fixture.transaction(
        DoobieVideoDao.findByVideoPath(fixture.videoFileResource.path)
      )

      _ <- IO.delay {
        maybeVideo mustBe defined
        maybeVideo.value.videoMetadata.id mustBe fixture.videoMetadata.id
      }
    } yield ()
  }

  it should "return None when video is not found" in runTest { fixture =>
    for {
      maybeVideo <- fixture.transaction(DoobieVideoDao.findById("non-existent-id", None))

      _ <- IO.delay {
        maybeVideo mustBe None
      }
    } yield ()
  }

  it should "search videos with no filters" in runTest { fixture =>
    for {
      results <- fixture.transaction(
        DoobieVideoDao.search(
          term = None,
          videoUrls = None,
          durationRange = RangeValue.all[FiniteDuration],
          sizeRange = RangeValue.all[Long],
          pageNumber = 0,
          pageSize = 10,
          sortBy = SortBy.Date,
          order = Order.Descending,
          videoSites = None,
          maybeUserId = None
        )
      )

      _ <- IO.delay {
        results.size mustBe 1
        results.head.videoMetadata.id mustBe fixture.videoMetadata.id
      }
    } yield ()
  }

  it should "search videos with title filter" in runTest { fixture =>
    for {
      resultsMatching <- fixture.transaction(
        DoobieVideoDao.search(
          term = Some("Test"),
          videoUrls = None,
          durationRange = RangeValue.all[FiniteDuration],
          sizeRange = RangeValue.all[Long],
          pageNumber = 0,
          pageSize = 10,
          sortBy = SortBy.Date,
          order = Order.Descending,
          videoSites = None,
          maybeUserId = None
        )
      )

      resultsNotMatching <- fixture.transaction(
        DoobieVideoDao.search(
          term = Some("NonExistent"),
          videoUrls = None,
          durationRange = RangeValue.all[FiniteDuration],
          sizeRange = RangeValue.all[Long],
          pageNumber = 0,
          pageSize = 10,
          sortBy = SortBy.Date,
          order = Order.Descending,
          videoSites = None,
          maybeUserId = None
        )
      )

      _ <- IO.delay {
        resultsMatching.size mustBe 1
        resultsNotMatching mustBe empty
      }
    } yield ()
  }

  it should "search videos with duration range filter" in runTest { fixture =>
    for {
      resultsWithinRange <- fixture.transaction(
        DoobieVideoDao.search(
          term = None,
          videoUrls = None,
          durationRange = RangeValue[FiniteDuration](Some(1 minute), Some(10 minutes)),
          sizeRange = RangeValue.all[Long],
          pageNumber = 0,
          pageSize = 10,
          sortBy = SortBy.Date,
          order = Order.Descending,
          videoSites = None,
          maybeUserId = None
        )
      )

      resultsOutsideRange <- fixture.transaction(
        DoobieVideoDao.search(
          term = None,
          videoUrls = None,
          durationRange = RangeValue[FiniteDuration](Some(10 minutes), None),
          sizeRange = RangeValue.all[Long],
          pageNumber = 0,
          pageSize = 10,
          sortBy = SortBy.Date,
          order = Order.Descending,
          videoSites = None,
          maybeUserId = None
        )
      )

      _ <- IO.delay {
        resultsWithinRange.size mustBe 1
        resultsOutsideRange mustBe empty
      }
    } yield ()
  }

  it should "search videos with size range filter" in runTest { fixture =>
    for {
      resultsWithinRange <- fixture.transaction(
        DoobieVideoDao.search(
          term = None,
          videoUrls = None,
          durationRange = RangeValue.all[FiniteDuration],
          sizeRange = RangeValue[Long](Some(10000L), Some(100000L)),
          pageNumber = 0,
          pageSize = 10,
          sortBy = SortBy.Date,
          order = Order.Descending,
          videoSites = None,
          maybeUserId = None
        )
      )

      resultsOutsideRange <- fixture.transaction(
        DoobieVideoDao.search(
          term = None,
          videoUrls = None,
          durationRange = RangeValue.all[FiniteDuration],
          sizeRange = RangeValue[Long](Some(100000L), None),
          pageNumber = 0,
          pageSize = 10,
          sortBy = SortBy.Date,
          order = Order.Descending,
          videoSites = None,
          maybeUserId = None
        )
      )

      _ <- IO.delay {
        resultsWithinRange.size mustBe 1
        resultsOutsideRange mustBe empty
      }
    } yield ()
  }

  it should "search videos with video site filter" in runTest { fixture =>
    for {
      resultsMatching <- fixture.transaction(
        DoobieVideoDao.search(
          term = None,
          videoUrls = None,
          durationRange = RangeValue.all[FiniteDuration],
          sizeRange = RangeValue.all[Long],
          pageNumber = 0,
          pageSize = 10,
          sortBy = SortBy.Date,
          order = Order.Descending,
          videoSites = Some(NonEmptyList.one(CustomVideoSite.SpankBang)),
          maybeUserId = None
        )
      )

      resultsNotMatching <- fixture.transaction(
        DoobieVideoDao.search(
          term = None,
          videoUrls = None,
          durationRange = RangeValue.all[FiniteDuration],
          sizeRange = RangeValue.all[Long],
          pageNumber = 0,
          pageSize = 10,
          sortBy = SortBy.Date,
          order = Order.Descending,
          videoSites = Some(NonEmptyList.one(CustomVideoSite.PornOne)),
          maybeUserId = None
        )
      )

      _ <- IO.delay {
        resultsMatching.size mustBe 1
        resultsNotMatching mustBe empty
      }
    } yield ()
  }

  it should "search videos with URL filter" in runTest { fixture =>
    for {
      resultsMatching <- fixture.transaction(
        DoobieVideoDao.search(
          term = None,
          videoUrls = Some(NonEmptyList.one(uri"https://example.com/video")),
          durationRange = RangeValue.all[FiniteDuration],
          sizeRange = RangeValue.all[Long],
          pageNumber = 0,
          pageSize = 10,
          sortBy = SortBy.Date,
          order = Order.Descending,
          videoSites = None,
          maybeUserId = None
        )
      )

      resultsNotMatching <- fixture.transaction(
        DoobieVideoDao.search(
          term = None,
          videoUrls = Some(NonEmptyList.one(uri"https://example.com/other")),
          durationRange = RangeValue.all[FiniteDuration],
          sizeRange = RangeValue.all[Long],
          pageNumber = 0,
          pageSize = 10,
          sortBy = SortBy.Date,
          order = Order.Descending,
          videoSites = None,
          maybeUserId = None
        )
      )

      _ <- IO.delay {
        resultsMatching.size mustBe 1
        resultsNotMatching mustBe empty
      }
    } yield ()
  }

  it should "increment watch time" in runTest { fixture =>
    val increment = 30 seconds

    for {
      maybeNewWatchTime <- fixture.transaction(
        DoobieVideoDao.incrementWatchTime(fixture.video.videoMetadata.id, increment)
      )

      maybeVideo <- fixture.transaction(DoobieVideoDao.findById(fixture.video.videoMetadata.id, None))

      _ <- IO.delay {
        maybeNewWatchTime mustBe defined
        maybeNewWatchTime.value mustBe increment
        maybeVideo.value.watchTime mustBe increment
      }

      maybeNewWatchTime2 <- fixture.transaction(
        DoobieVideoDao.incrementWatchTime(fixture.video.videoMetadata.id, 1 minute)
      )

      _ <- IO.delay {
        maybeNewWatchTime2 mustBe defined
        maybeNewWatchTime2.value mustBe (increment + (1 minute))
      }
    } yield ()
  }

  it should "return None when incrementing watch time for non-existent video" in runTest { fixture =>
    for {
      result <- fixture.transaction(
        DoobieVideoDao.incrementWatchTime("non-existent-id", 30 seconds)
      )

      _ <- IO.delay {
        result mustBe None
      }
    } yield ()
  }

  it should "delete video by ID" in runTest { fixture =>
    for {
      deleteCount <- fixture.transaction(DoobieVideoDao.deleteById(fixture.video.videoMetadata.id))

      maybeVideo <- fixture.transaction(DoobieVideoDao.findById(fixture.video.videoMetadata.id, None))

      _ <- IO.delay {
        deleteCount mustBe 2
        maybeVideo mustBe None
      }
    } yield ()
  }

  it should "return 0 when deleting non-existent video" in runTest { fixture =>
    for {
      deleteCount <- fixture.transaction(DoobieVideoDao.deleteById("non-existent-id"))

      _ <- IO.delay {
        deleteCount mustBe 0
      }
    } yield ()
  }

  it should "count total videos" in runTest { fixture =>
    for {
      count <- fixture.transaction(DoobieVideoDao.count)

      _ <- IO.delay {
        count mustBe 1
      }
    } yield ()
  }

  it should "calculate total duration" in runTest { fixture =>
    for {
      totalDuration <- fixture.transaction(DoobieVideoDao.duration)

      _ <- IO.delay {
        totalDuration mustBe (5 minutes)
      }
    } yield ()
  }

  it should "calculate total size" in runTest { fixture =>
    for {
      totalSize <- fixture.transaction(DoobieVideoDao.size)

      _ <- IO.delay {
        totalSize mustBe 50000L
      }
    } yield ()
  }

  it should "return distinct video sites" in runTest { fixture =>
    for {
      videoSites <- fixture.transaction(DoobieVideoDao.sites)

      _ <- IO.delay {
        videoSites mustBe Set[VideoSite](CustomVideoSite.SpankBang)
      }
    } yield ()
  }

  it should "check video file permission" in runTest { fixture =>
    import com.ruchij.core.daos.scheduling.models.SchedulingStatus

    for {
      timestamp <- Clock[IO].timestamp
      userId = "test-user-id"

      _ <- fixture.transaction(insertTestUser(userId, "test@example.com", timestamp))

      _ <- fixture.transaction {
        sql"""
          INSERT INTO scheduled_video (scheduled_at, last_updated_at, status, downloaded_bytes, video_metadata_id, completed_at)
            VALUES ($timestamp, $timestamp, ${SchedulingStatus.Completed: SchedulingStatus}, 0, ${fixture.video.videoMetadata.id}, $timestamp)
        """.update.run
      }

      hasPermissionBefore <- fixture.transaction(
        DoobieVideoDao.hasVideoFilePermission(fixture.videoFileResource.id, userId)
      )
      _ <- IO.delay { hasPermissionBefore mustBe false }

      _ <- fixture.transaction(
        DoobieVideoPermissionDao.insert(VideoPermission(timestamp, fixture.video.videoMetadata.id, userId))
      )

      hasPermissionAfter <- fixture.transaction(
        DoobieVideoDao.hasVideoFilePermission(fixture.videoFileResource.id, userId)
      )
      _ <- IO.delay { hasPermissionAfter mustBe true }
    } yield ()
  }

  it should "support pagination in search results" in runTest { fixture =>
    for {
      timestamp <- Clock[IO].timestamp

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
        "Second Video",
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

      page1 <- fixture.transaction(
        DoobieVideoDao.search(
          term = None,
          videoUrls = None,
          durationRange = RangeValue.all[FiniteDuration],
          sizeRange = RangeValue.all[Long],
          pageNumber = 0,
          pageSize = 1,
          sortBy = SortBy.Date,
          order = Order.Descending,
          videoSites = None,
          maybeUserId = None
        )
      )

      page2 <- fixture.transaction(
        DoobieVideoDao.search(
          term = None,
          videoUrls = None,
          durationRange = RangeValue.all[FiniteDuration],
          sizeRange = RangeValue.all[Long],
          pageNumber = 1,
          pageSize = 1,
          sortBy = SortBy.Date,
          order = Order.Descending,
          videoSites = None,
          maybeUserId = None
        )
      )

      allResults <- fixture.transaction(
        DoobieVideoDao.search(
          term = None,
          videoUrls = None,
          durationRange = RangeValue.all[FiniteDuration],
          sizeRange = RangeValue.all[Long],
          pageNumber = 0,
          pageSize = 10,
          sortBy = SortBy.Date,
          order = Order.Descending,
          videoSites = None,
          maybeUserId = None
        )
      )

      _ <- IO.delay {
        page1.size mustBe 1
        page2.size mustBe 1
        allResults.size mustBe 2
        page1.head.videoMetadata.id must not be page2.head.videoMetadata.id
      }
    } yield ()
  }

  it should "sort videos by different criteria" in runTest { fixture =>
    for {
      timestamp <- Clock[IO].timestamp

      thumbnailFileResource2 = FileResource(
        "thumbnail-id-2",
        timestamp.plus(java.time.Duration.ofHours(1)),
        "/opt/image/thumbnail2.jpg",
        MediaType.image.jpeg,
        1000
      )
      _ <- fixture.transaction(DoobieFileResourceDao.insert(thumbnailFileResource2))

      videoMetadata2 = VideoMetadata(
        uri"https://example.com/video2",
        "test-video-id-2",
        CustomVideoSite.SpankBang,
        "Another Video",
        10 minutes,
        100000,
        thumbnailFileResource2
      )
      _ <- fixture.transaction(DoobieVideoMetadataDao.insert(videoMetadata2))

      videoFileResource2 = FileResource(
        "video-file-id-2",
        timestamp.plus(java.time.Duration.ofHours(1)),
        "/opt/video/test-video2.mp4",
        MediaType.video.mp4,
        100000
      )
      _ <- fixture.transaction(DoobieFileResourceDao.insert(videoFileResource2))
      _ <- fixture.transaction(DoobieVideoDao.insert("test-video-id-2", videoFileResource2.id, timestamp.plus(java.time.Duration.ofHours(1)), 0 seconds))

      resultsByDateDesc <- fixture.transaction(
        DoobieVideoDao.search(
          term = None,
          videoUrls = None,
          durationRange = RangeValue.all[FiniteDuration],
          sizeRange = RangeValue.all[Long],
          pageNumber = 0,
          pageSize = 10,
          sortBy = SortBy.Date,
          order = Order.Descending,
          videoSites = None,
          maybeUserId = None
        )
      )

      resultsByDateAsc <- fixture.transaction(
        DoobieVideoDao.search(
          term = None,
          videoUrls = None,
          durationRange = RangeValue.all[FiniteDuration],
          sizeRange = RangeValue.all[Long],
          pageNumber = 0,
          pageSize = 10,
          sortBy = SortBy.Date,
          order = Order.Ascending,
          videoSites = None,
          maybeUserId = None
        )
      )

      resultsByDuration <- fixture.transaction(
        DoobieVideoDao.search(
          term = None,
          videoUrls = None,
          durationRange = RangeValue.all[FiniteDuration],
          sizeRange = RangeValue.all[Long],
          pageNumber = 0,
          pageSize = 10,
          sortBy = SortBy.Duration,
          order = Order.Descending,
          videoSites = None,
          maybeUserId = None
        )
      )

      resultsBySize <- fixture.transaction(
        DoobieVideoDao.search(
          term = None,
          videoUrls = None,
          durationRange = RangeValue.all[FiniteDuration],
          sizeRange = RangeValue.all[Long],
          pageNumber = 0,
          pageSize = 10,
          sortBy = SortBy.Size,
          order = Order.Descending,
          videoSites = None,
          maybeUserId = None
        )
      )

      _ <- IO.delay {
        resultsByDateDesc.head.videoMetadata.id mustBe "test-video-id-2"
        resultsByDateAsc.head.videoMetadata.id mustBe fixture.video.videoMetadata.id
        resultsByDuration.head.videoMetadata.duration mustBe (10 minutes)
        resultsBySize.head.videoMetadata.size mustBe 100000L
      }
    } yield ()
  }

  it should "sort videos by watch time" in runTest { fixture =>
    for {
      timestamp <- Clock[IO].timestamp

      _ <- fixture.transaction(DoobieVideoDao.incrementWatchTime(fixture.video.videoMetadata.id, 2 minutes))

      thumbnailFileResource2 = FileResource(
        "thumbnail-id-2",
        timestamp.plus(java.time.Duration.ofHours(1)),
        "/opt/image/thumbnail2.jpg",
        MediaType.image.jpeg,
        1000
      )
      _ <- fixture.transaction(DoobieFileResourceDao.insert(thumbnailFileResource2))

      videoMetadata2 = VideoMetadata(
        uri"https://example.com/video2",
        "test-video-id-2",
        CustomVideoSite.SpankBang,
        "Another Video",
        10 minutes,
        100000,
        thumbnailFileResource2
      )
      _ <- fixture.transaction(DoobieVideoMetadataDao.insert(videoMetadata2))

      videoFileResource2 = FileResource(
        "video-file-id-2",
        timestamp.plus(java.time.Duration.ofHours(1)),
        "/opt/video/test-video2.mp4",
        MediaType.video.mp4,
        100000
      )
      _ <- fixture.transaction(DoobieFileResourceDao.insert(videoFileResource2))
      _ <- fixture.transaction(DoobieVideoDao.insert("test-video-id-2", videoFileResource2.id, timestamp.plus(java.time.Duration.ofHours(1)), 5 minutes))

      resultsByWatchTimeDesc <- fixture.transaction(
        DoobieVideoDao.search(
          term = None,
          videoUrls = None,
          durationRange = RangeValue.all[FiniteDuration],
          sizeRange = RangeValue.all[Long],
          pageNumber = 0,
          pageSize = 10,
          sortBy = SortBy.WatchTime,
          order = Order.Descending,
          videoSites = None,
          maybeUserId = None
        )
      )

      resultsByWatchTimeAsc <- fixture.transaction(
        DoobieVideoDao.search(
          term = None,
          videoUrls = None,
          durationRange = RangeValue.all[FiniteDuration],
          sizeRange = RangeValue.all[Long],
          pageNumber = 0,
          pageSize = 10,
          sortBy = SortBy.WatchTime,
          order = Order.Ascending,
          videoSites = None,
          maybeUserId = None
        )
      )

      _ <- IO.delay {
        resultsByWatchTimeDesc.head.videoMetadata.id mustBe "test-video-id-2"
        resultsByWatchTimeAsc.head.videoMetadata.id mustBe fixture.video.videoMetadata.id
      }
    } yield ()
  }

  it should "sort videos randomly" in runTest { fixture =>
    for {
      resultsByRandom <- fixture.transaction(
        DoobieVideoDao.search(
          term = None,
          videoUrls = None,
          durationRange = RangeValue.all[FiniteDuration],
          sizeRange = RangeValue.all[Long],
          pageNumber = 0,
          pageSize = 10,
          sortBy = SortBy.Title,
          order = Order.Descending,
          videoSites = None,
          maybeUserId = None
        )
      )

      _ <- IO.delay {
        resultsByRandom.size mustBe 1
      }
    } yield ()
  }

  it should "search videos with user ID and permissions" in runTest { fixture =>
    for {
      timestamp <- Clock[IO].timestamp
      userId = "test-user-id-search"

      _ <- fixture.transaction(insertTestUser(userId, "search@example.com", timestamp))

      _ <- fixture.transaction {
        sql"""
          INSERT INTO scheduled_video (scheduled_at, last_updated_at, status, downloaded_bytes, video_metadata_id, completed_at)
            VALUES ($timestamp, $timestamp, ${SchedulingStatus.Completed: SchedulingStatus}, 0, ${fixture.video.videoMetadata.id}, $timestamp)
        """.update.run
      }

      _ <- fixture.transaction(
        DoobieVideoPermissionDao.insert(VideoPermission(timestamp, fixture.video.videoMetadata.id, userId))
      )

      _ <- fixture.transaction {
        sql"""
          INSERT INTO video_title (video_id, user_id, title)
            VALUES (${fixture.video.videoMetadata.id}, $userId, 'Custom Title')
        """.update.run
      }

      resultsWithUser <- fixture.transaction(
        DoobieVideoDao.search(
          term = None,
          videoUrls = None,
          durationRange = RangeValue.all[FiniteDuration],
          sizeRange = RangeValue.all[Long],
          pageNumber = 0,
          pageSize = 10,
          sortBy = SortBy.Date,
          order = Order.Descending,
          videoSites = None,
          maybeUserId = Some(userId)
        )
      )

      resultsWithOtherUser <- fixture.transaction(
        DoobieVideoDao.search(
          term = None,
          videoUrls = None,
          durationRange = RangeValue.all[FiniteDuration],
          sizeRange = RangeValue.all[Long],
          pageNumber = 0,
          pageSize = 10,
          sortBy = SortBy.Date,
          order = Order.Descending,
          videoSites = None,
          maybeUserId = Some("other-user-id")
        )
      )

      _ <- IO.delay {
        resultsWithUser.size mustBe 1
        resultsWithUser.head.videoMetadata.id mustBe fixture.video.videoMetadata.id
        resultsWithOtherUser mustBe empty
      }
    } yield ()
  }

  it should "find video by ID with user permission" in runTest { fixture =>
    import com.ruchij.core.daos.scheduling.models.SchedulingStatus

    for {
      timestamp <- Clock[IO].timestamp
      userId = "test-user-find"

      _ <- fixture.transaction(insertTestUser(userId, "find@example.com", timestamp))

      _ <- fixture.transaction {
        sql"""
          INSERT INTO scheduled_video (scheduled_at, last_updated_at, status, downloaded_bytes, video_metadata_id, completed_at)
            VALUES ($timestamp, $timestamp, ${SchedulingStatus.Completed: SchedulingStatus}, 0, ${fixture.video.videoMetadata.id}, $timestamp)
        """.update.run
      }

      _ <- fixture.transaction(
        DoobieVideoPermissionDao.insert(VideoPermission(timestamp, fixture.video.videoMetadata.id, userId))
      )

      _ <- fixture.transaction {
        sql"""
          INSERT INTO video_title (video_id, user_id, title)
            VALUES (${fixture.video.videoMetadata.id}, $userId, 'Custom Title')
        """.update.run
      }

      videoWithPermission <- fixture.transaction(
        DoobieVideoDao.findById(fixture.video.videoMetadata.id, Some(userId))
      )

      videoWithoutPermission <- fixture.transaction(
        DoobieVideoDao.findById(fixture.video.videoMetadata.id, Some("other-user"))
      )

      _ <- IO.delay {
        videoWithPermission mustBe defined
        videoWithPermission.value.videoMetadata.id mustBe fixture.video.videoMetadata.id
        videoWithoutPermission mustBe None
      }
    } yield ()
  }

  it should "return zero duration when no videos exist" in runTest { fixture =>
    for {
      _ <- fixture.transaction(DoobieVideoDao.deleteById(fixture.video.videoMetadata.id))

      totalDuration <- fixture.transaction(DoobieVideoDao.duration)

      _ <- IO.delay {
        totalDuration mustBe (0 seconds)
      }
    } yield ()
  }

  it should "return zero size when no videos exist" in runTest { fixture =>
    for {
      _ <- fixture.transaction(DoobieVideoDao.deleteById(fixture.video.videoMetadata.id))

      totalSize <- fixture.transaction(DoobieVideoDao.size)

      _ <- IO.delay {
        totalSize mustBe 0L
      }
    } yield ()
  }

  it should "return empty set for sites when no videos exist" in runTest { fixture =>
    for {
      _ <- fixture.transaction(DoobieVideoDao.deleteById(fixture.video.videoMetadata.id))

      videoSites <- fixture.transaction(DoobieVideoDao.sites)

      _ <- IO.delay {
        videoSites mustBe empty
      }
    } yield ()
  }
}
