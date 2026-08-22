package com.ruchij.batch.services.sync

import cats.arrow.FunctionK
import cats.data.NonEmptyList
import cats.effect.{IO, Ref}
import cats.implicits._
import cats.~>
import com.ruchij.batch.daos.filesync.FileSyncDao
import com.ruchij.batch.daos.filesync.models.FileSync
import com.ruchij.batch.services.enrichment.VideoEnrichmentService
import com.ruchij.batch.services.video.BatchVideoService
import com.ruchij.core.config.StorageConfiguration
import com.ruchij.core.daos.resource.FileResourceDao
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.scheduling.SchedulingDao
import com.ruchij.core.daos.scheduling.models.{RangeValue, ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.daos.snapshot.SnapshotDao
import com.ruchij.core.daos.snapshot.models.Snapshot
import com.ruchij.core.daos.video.VideoDao
import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.daos.videometadata.VideoMetadataDao
import com.ruchij.core.daos.videometadata.models.{VideoMetadata, VideoSite}
import com.ruchij.core.daos.workers.models.VideoScan.ScanStatus
import com.ruchij.core.daos.workers.models.VideoScan
import com.ruchij.core.exceptions.ResourceNotFoundException
import com.ruchij.core.kv.codecs.{KVCodec, KVDecoder}
import com.ruchij.core.kv.keys.KeySpacedKeyEncoder
import com.ruchij.core.kv.{InMemoryKeyValueStore, KeySpacedKeyValueStore}
import com.ruchij.core.services.config.models.SharedConfigKey
import com.ruchij.core.services.config.models.SharedConfigKey.{SharedConfigKeySpace, VideoScanningStatus}
import com.ruchij.core.services.config.{ConfigurationService, ConfigurationServiceImpl}
import com.ruchij.core.services.hashing.HashingService
import com.ruchij.core.services.models.{Order, SortBy}
import com.ruchij.core.services.repository.FileRepositoryService.FileRepository
import com.ruchij.core.services.repository.{FileTypeDetector, InMemoryRepositoryService}
import com.ruchij.core.services.video.VideoAnalysisService
import com.ruchij.core.services.video.VideoAnalysisService.VideoMetadataResult
import com.ruchij.core.services.video.models.VideoAnalysisResult
import com.ruchij.core.test.IOSupport.{IOWrapper, runIO}
import com.ruchij.core.test.Providers
import com.ruchij.core.types.{Clock, TimeUtils}
import fs2.Stream
import org.http4s.{MediaType, Uri}
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
  * Covers the behaviour of [[SynchronizationServiceImpl.sync]].
  *
  * `SynchronizationServiceImplSpec` only exercises the pure `videoIdFromVideoFile` helper and the
  * result models, which leaves the synchronization logic itself untested. These tests drive `sync`
  * through in-memory DAOs and an in-memory file repository, so every branch runs without Docker.
  */
class SynchronizationServiceImplBehaviourSpec extends AnyFlatSpec with Matchers with OptionValues {

  private val Timestamp: Instant = TimeUtils.instantOf(2024, 5, 15, 10, 30)

  private val StorageConfig =
    StorageConfiguration(videoFolder = "/videos", imageFolder = "/images", otherVideoFolders = List("/other-videos"))

  "sync" should "add a video file that the database does not know about" in runIO {
    val videoPath = "/videos/spankbang-abc123.mp4"

    for {
      fixture <- TestFixture.create(files = List(videoPath))
      result <- fixture.synchronizationService.sync

      _ <- IO.delay {
        result.syncedVideos mustBe 1
        result.ignoredFiles mustBe 0
        result.syncErrors mustBe 0
      }

      // The video, its metadata, the thumbnail and a completed scheduled download are all persisted
      videos <- fixture.videos.get
      _ <- IO.delay {
        videos.values.map(_.fileResource.path).toList mustBe List(videoPath)
        videos.values.head.videoMetadata.videoSite mustBe VideoSite.Local
        videos.values.head.videoMetadata.title mustBe "spankbang-abc123.mp4"
        videos.values.head.videoMetadata.duration mustBe 10.minutes
      }

      videoMetadata <- fixture.videoMetadata.get
      scheduledDownloads <- fixture.scheduledVideoDownloads.get
      _ <- IO.delay {
        videoMetadata.keys.toList mustBe List("local-hash-of-/videos/spankbang-abc123.mp4")
        scheduledDownloads.keys.toList mustBe List("local-hash-of-/videos/spankbang-abc123.mp4")
      }

      // The file sync row is marked as completed so the file is not re-synced
      fileSyncs <- fixture.fileSyncs.get
      _ <- IO.delay { fileSyncs(videoPath).syncedAt mustBe defined }
    } yield (): Unit
  }

  it should "scan every configured video folder" in runIO {
    for {
      fixture <- TestFixture.create(files = List("/videos/one.mp4", "/other-videos/two.mp4"))
      result <- fixture.synchronizationService.sync

      _ <- IO.delay { result.syncedVideos mustBe 2 }
    } yield (): Unit
  }

  it should "ignore files that do not have a video file extension" in runIO {
    for {
      fixture <- TestFixture.create(files = List("/videos/notes.txt"))
      result <- fixture.synchronizationService.sync

      _ <- IO.delay {
        result.ignoredFiles mustBe 1
        result.syncedVideos mustBe 0
      }
    } yield (): Unit
  }

  it should "ignore files with a video extension that are not detected as videos" in runIO {
    for {
      fixture <- TestFixture.create(files = List("/videos/renamed.mp4"), detectedMediaType = MediaType.text.plain)
      result <- fixture.synchronizationService.sync

      _ <- IO.delay {
        result.ignoredFiles mustBe 1
        result.syncedVideos mustBe 0
      }
    } yield (): Unit
  }

  it should "ignore a file when the file type cannot be detected" in runIO {
    for {
      fixture <- TestFixture.create(files = List("/videos/broken.mp4"))
      _ <- fixture.fileTypeDetectorError.set(Some(new RuntimeException("Unable to read the file")))

      result <- fixture.synchronizationService.sync

      _ <- IO.delay {
        result.ignoredFiles mustBe 1
        result.syncedVideos mustBe 0
      }
    } yield (): Unit
  }

  it should "report a file that is already a known video as an existing video" in runIO {
    val videoPath = "/videos/existing.mp4"

    for {
      fixture <- TestFixture.create(files = List(videoPath))
      _ <- fixture.insertVideo(videoId = "existing-video", videoPath = videoPath, snapshotCount = 12)

      result <- fixture.synchronizationService.sync

      _ <- IO.delay {
        result.existingVideoFiles mustBe 1
        result.syncedVideos mustBe 0
        result.missingVideoFiles mustBe 0
      }
    } yield (): Unit
  }

  it should "delete videos whose video file no longer exists" in runIO {
    for {
      fixture <- TestFixture.create(files = List.empty)
      _ <- fixture.insertVideo(videoId = "deleted-video", videoPath = "/videos/gone.mp4", snapshotCount = 12)

      result <- fixture.synchronizationService.sync

      _ <- IO.delay { result.missingVideoFiles mustBe 1 }

      videos <- fixture.videos.get
      deletedVideoIds <- fixture.deletedVideoIds.get
      _ <- IO.delay {
        videos mustBe empty

        // The video file itself is left alone, only the database rows are removed
        deletedVideoIds mustBe List("deleted-video" -> false)
      }
    } yield (): Unit
  }

  it should "recreate the snapshots of videos that do not have the expected snapshot count" in runIO {
    val videoPath = "/videos/missing-snapshots.mp4"

    for {
      fixture <- TestFixture.create(files = List(videoPath))
      _ <- fixture.insertVideo(videoId = "snapshot-video", videoPath = videoPath, snapshotCount = 3)

      result <- fixture.synchronizationService.sync

      _ <- IO.delay {
        result.videoCountOfSnapshotsUpdated mustBe 1
        result.existingVideoFiles mustBe 1
      }

      snapshots <- fixture.snapshots.get
      enrichedVideoIds <- fixture.enrichedVideoIds.get
      _ <- IO.delay {
        // The stale snapshots are deleted before the enrichment service regenerates them
        snapshots.get("snapshot-video") mustBe None
        enrichedVideoIds mustBe List("snapshot-video")
      }
    } yield (): Unit
  }

  it should "leave videos with the expected snapshot count alone" in runIO {
    val videoPath = "/videos/complete.mp4"

    for {
      fixture <- TestFixture.create(files = List(videoPath))
      _ <- fixture.insertVideo(
        videoId = "complete-video",
        videoPath = videoPath,
        snapshotCount = VideoEnrichmentService.SnapshotCount
      )

      result <- fixture.synchronizationService.sync

      _ <- IO.delay { result.videoCountOfSnapshotsUpdated mustBe 0 }

      enrichedVideoIds <- fixture.enrichedVideoIds.get
      _ <- IO.delay { enrichedVideoIds mustBe empty }
    } yield (): Unit
  }

  it should "report a sync error when the video file cannot be added" in runIO {
    for {
      fixture <- TestFixture.create(files = List("/videos/no-duration.mp4"))
      _ <- fixture.videoDurationError.set(Some(new RuntimeException("ffprobe failed")))

      result <- fixture.synchronizationService.sync

      _ <- IO.delay {
        result.syncErrors mustBe 1
        result.syncedVideos mustBe 0
      }
    } yield (): Unit
  }

  it should "page through every video when there are more videos than the page size" in runIO {
    val videoPaths = (1 to 120).map(index => s"/videos/video-$index.mp4").toList

    for {
      fixture <- TestFixture.create(files = List.empty)
      _ <- videoPaths.zipWithIndex.traverse {
        case (videoPath, index) =>
          fixture.insertVideo(videoId = s"video-$index", videoPath = videoPath, snapshotCount = 12)
      }

      result <- fixture.synchronizationService.sync

      // Every video is missing its file, so paging has to reach all 120 of them
      _ <- IO.delay { result.missingVideoFiles mustBe 120 }
    } yield (): Unit
  }

  "sync" should "set the scanning status to in progress and back to idle" in runIO {
    for {
      fixture <- TestFixture.create(files = List("/videos/status.mp4"))
      _ <- fixture.synchronizationService.sync

      scanStatuses <- fixture.scanStatuses.get
      _ <- IO.delay { scanStatuses mustBe List(ScanStatus.InProgress, ScanStatus.Idle) }

      videoScan <- fixture.sharedConfigurationService.get(VideoScanningStatus)
      _ <- IO.delay { videoScan.value.status mustBe ScanStatus.Idle }
    } yield (): Unit
  }

  it should "set the scanning status to error and re-raise when synchronization fails" in runIO {
    val failure = new RuntimeException("Unable to list the video folder")

    for {
      fixture <- TestFixture.create(files = List.empty, listError = Some(failure))
      throwable <- fixture.synchronizationService.sync.error

      _ <- IO.delay { throwable mustBe failure }

      videoScan <- fixture.sharedConfigurationService.get(VideoScanningStatus)
      _ <- IO.delay { videoScan.value.status mustBe ScanStatus.Error }
    } yield (): Unit
  }

  "syncVideo" should "retry a file whose previous sync attempt was abandoned" in runIO {
    val videoPath = "/videos/abandoned.mp4"

    for {
      fixture <- TestFixture.create(files = List(videoPath))

      // A file sync row that was locked over a minute ago and never completed
      _ <- fixture.fileSyncs.update(_ + (videoPath -> FileSync(Timestamp.minusSeconds(600), videoPath, None)))

      result <- fixture.synchronizationService.sync

      _ <- IO.delay { result.syncedVideos mustBe 1 }
    } yield (): Unit
  }

  it should "skip a file that another worker is currently syncing" in runIO {
    val videoPath = "/videos/in-flight.mp4"

    for {
      fixture <- TestFixture.create(files = List(videoPath))

      // A file sync row that was locked moments ago by another worker
      _ <- fixture.fileSyncs.update(_ + (videoPath -> FileSync(Timestamp.minusSeconds(5), videoPath, None)))

      result <- fixture.synchronizationService.sync

      _ <- IO.delay {
        result.existingVideoFiles mustBe 1
        result.syncedVideos mustBe 0
      }
    } yield (): Unit
  }

  it should "replace a stale file resource that points at the same path" in runIO {
    val videoPath = "/videos/stale-resource.mp4"

    for {
      fixture <- TestFixture.create(files = List(videoPath))
      _ <- fixture.fileResources.update {
        _ + ("stale" -> FileResource("stale", Timestamp, videoPath, MediaType.video.mp4, 1L))
      }

      result <- fixture.synchronizationService.sync

      _ <- IO.delay { result.syncedVideos mustBe 1 }

      fileResources <- fixture.fileResources.get
      _ <- IO.delay { fileResources.get("stale") mustBe None }
    } yield (): Unit
  }

  private class TestFixture(
    val synchronizationService: SynchronizationServiceImpl[IO, String, IO],
    val sharedConfigurationService: ConfigurationService[IO, SharedConfigKey],
    val fileResources: Ref[IO, Map[String, FileResource]],
    val videoMetadata: Ref[IO, Map[String, VideoMetadata]],
    val scheduledVideoDownloads: Ref[IO, Map[String, ScheduledVideoDownload]],
    val fileSyncs: Ref[IO, Map[String, FileSync]],
    val videos: Ref[IO, Map[String, Video]],
    val snapshots: Ref[IO, Map[String, Seq[Snapshot]]],
    val deletedVideoIds: Ref[IO, List[(String, Boolean)]],
    val enrichedVideoIds: Ref[IO, List[String]],
    val scanStatuses: Ref[IO, List[ScanStatus]],
    val videoDurationError: Ref[IO, Option[Throwable]],
    val fileTypeDetectorError: Ref[IO, Option[Throwable]]
  ) {
    def insertVideo(videoId: String, videoPath: String, snapshotCount: Int): IO[Unit] = {
      val thumbnail = FileResource(s"$videoId-thumbnail", Timestamp, s"/images/$videoId.jpg", MediaType.image.jpeg, 10L)
      val fileResource = FileResource(videoId, Timestamp, videoPath, MediaType.video.mp4, 1024L)

      val metadata =
        VideoMetadata(
          Uri.unsafeFromString(Uri.encode(videoPath)),
          videoId,
          VideoSite.Local,
          videoId,
          10.minutes,
          1024L,
          thumbnail
        )

      val videoSnapshots =
        (1 to snapshotCount).map { index =>
          Snapshot(
            videoId,
            FileResource(s"$videoId-snapshot-$index", Timestamp, s"/images/$videoId-$index.jpg", MediaType.image.jpeg, 5L),
            index.minutes
          )
        }

      videos.update(_ + (videoId -> Video(metadata, fileResource, Timestamp, FiniteDuration(0, "ms")))) *>
        videoMetadata.update(_ + (videoId -> metadata)) *>
        fileResources.update(_ ++ Map(thumbnail.id -> thumbnail, fileResource.id -> fileResource)) *>
        snapshots.update(_ + (videoId -> videoSnapshots))
    }
  }

  private object TestFixture {
    def create(
      files: List[String],
      detectedMediaType: MediaType = MediaType.video.mp4,
      listError: Option[Throwable] = None
    ): IO[TestFixture] =
      for {
        fileResources <- Ref.of[IO, Map[String, FileResource]](Map.empty)
        videoMetadata <- Ref.of[IO, Map[String, VideoMetadata]](Map.empty)
        scheduledVideoDownloads <- Ref.of[IO, Map[String, ScheduledVideoDownload]](Map.empty)
        fileSyncs <- Ref.of[IO, Map[String, FileSync]](Map.empty)
        videos <- Ref.of[IO, Map[String, Video]](Map.empty)
        snapshots <- Ref.of[IO, Map[String, Seq[Snapshot]]](Map.empty)
        deletedVideoIds <- Ref.of[IO, List[(String, Boolean)]](List.empty)
        enrichedVideoIds <- Ref.of[IO, List[String]](List.empty)
        scanStatuses <- Ref.of[IO, List[ScanStatus]](List.empty)
        videoDurationError <- Ref.of[IO, Option[Throwable]](None)
        fileTypeDetectorError <- Ref.of[IO, Option[Throwable]](None)

        fileRepositoryService <- fileRepository(files, listError)
      } yield {
        implicit val clock: Clock[IO] = Providers.stubClock[IO](Timestamp)
        implicit val transaction: IO ~> IO = FunctionK.id[IO]

        val sharedConfigurationService = configurationService(scanStatuses)

        val synchronizationService =
          new SynchronizationServiceImpl[IO, String, IO](
            fileRepositoryService,
            new StubFileResourceDao(fileResources),
            new StubVideoMetadataDao(videoMetadata),
            new StubSchedulingDao(scheduledVideoDownloads),
            new StubFileSyncDao(fileSyncs),
            new StubVideoDao(videos),
            new StubSnapshotDao(snapshots),
            new StubBatchVideoService(videos, videoMetadata, fileResources, deletedVideoIds),
            new StubVideoEnrichmentService(enrichedVideoIds),
            new StubHashingService,
            new StubVideoAnalysisService(videoDurationError),
            sharedConfigurationService,
            new StubFileTypeDetector(detectedMediaType, fileTypeDetectorError),
            StorageConfig
          )

        new TestFixture(
          synchronizationService,
          sharedConfigurationService,
          fileResources,
          videoMetadata,
          scheduledVideoDownloads,
          fileSyncs,
          videos,
          snapshots,
          deletedVideoIds,
          enrichedVideoIds,
          scanStatuses,
          videoDurationError,
          fileTypeDetectorError
        )
      }

    private def fileRepository(files: List[String], listError: Option[Throwable]): IO[FileRepository[IO, String]] =
      IO.delay {
        val concurrentHashMap = new ConcurrentHashMap[String, List[Byte]]()
        files.foreach(file => concurrentHashMap.put(file, List.fill(1024)(0: Byte)))

        new InMemoryRepositoryService[IO](concurrentHashMap) {
          override def list(key: Key): Stream[IO, Key] =
            listError.fold(super.list(key))(throwable => Stream.raiseError[IO](throwable))
        }
      }

    private def configurationService(
      scanStatuses: Ref[IO, List[ScanStatus]]
    )(implicit keySpacedKeyEncoder: KeySpacedKeyEncoder[IO, SharedConfigKey[_]]): ConfigurationService[IO, SharedConfigKey] = {
      val delegate =
        new ConfigurationServiceImpl[IO, SharedConfigKey](
          new KeySpacedKeyValueStore[IO, SharedConfigKey[_], String](
            SharedConfigKeySpace,
            new InMemoryKeyValueStore[IO]
          )
        )

      new RecordingConfigurationService(delegate, scanStatuses)
    }
  }

  private class RecordingConfigurationService(
    delegate: ConfigurationService[IO, SharedConfigKey],
    scanStatuses: Ref[IO, List[ScanStatus]]
  ) extends ConfigurationService[IO, SharedConfigKey] {
    override def get[A: KVDecoder[IO, *], K[_] <: SharedConfigKey[_]](key: K[A]): IO[Option[A]] =
      delegate.get(key)

    override def put[A: KVCodec[IO, *], K[_] <: SharedConfigKey[_]](key: K[A], value: A): IO[Option[A]] =
      IO.defer {
        value match {
          case VideoScan(_, scanStatus) => scanStatuses.update(_ :+ scanStatus)
          case _ => IO.unit
        }
      }
        .productR(delegate.put(key, value))

    override def delete[A: KVDecoder[IO, *], K[_] <: SharedConfigKey[_]](key: K[A]): IO[Option[A]] =
      delegate.delete(key)
  }

  private class StubFileResourceDao(fileResources: Ref[IO, Map[String, FileResource]]) extends FileResourceDao[IO] {
    override def insert(resource: FileResource): IO[Int] =
      fileResources.update(_ + (resource.id -> resource)).as(1)

    override def findByPath(path: String): IO[Option[FileResource]] =
      fileResources.get.map(_.values.find(_.path == path))

    override def deleteById(id: String): IO[Int] =
      fileResources.modify(values => (values - id, if (values.contains(id)) 1 else 0))

    override def update(id: String, size: Long): IO[Int] = notImplemented

    override def getById(id: String): IO[Option[FileResource]] = notImplemented
  }

  private class StubVideoMetadataDao(videoMetadata: Ref[IO, Map[String, VideoMetadata]])
      extends VideoMetadataDao[IO] {
    override def insert(metadata: VideoMetadata): IO[Int] =
      videoMetadata.update(_ + (metadata.id -> metadata)).as(1)

    override def update(
      videoMetadataId: String,
      title: Option[String],
      size: Option[Long],
      maybeDuration: Option[FiniteDuration]
    ): IO[Int] = notImplemented

    override def findById(videoMetadataId: String): IO[Option[VideoMetadata]] = notImplemented

    override def isThumbnailFileResource(thumbnailId: String): IO[Boolean] = notImplemented

    override def findByUrl(uri: Uri): IO[Option[VideoMetadata]] = notImplemented

    override def deleteById(videoMetadataId: String): IO[Int] = notImplemented
  }

  private class StubSchedulingDao(scheduledVideoDownloads: Ref[IO, Map[String, ScheduledVideoDownload]])
      extends SchedulingDao[IO] {
    override def insert(scheduledVideoDownload: ScheduledVideoDownload): IO[Int] =
      scheduledVideoDownloads
        .update(_ + (scheduledVideoDownload.videoMetadata.id -> scheduledVideoDownload))
        .as(1)

    override def getById(id: String, maybeUserId: Option[String]): IO[Option[ScheduledVideoDownload]] =
      scheduledVideoDownloads.get.map(_.get(id))

    override def markScheduledVideoDownloadAsComplete(
      id: String,
      timestamp: Instant
    ): IO[Option[ScheduledVideoDownload]] = notImplemented

    override def updateSchedulingStatusById(
      id: String,
      status: SchedulingStatus,
      timestamp: Instant
    ): IO[Option[ScheduledVideoDownload]] = notImplemented

    override def setErrorById(id: String, throwable: Throwable, timestamp: Instant): IO[Option[ScheduledVideoDownload]] =
      notImplemented

    override def updateSchedulingStatus(
      from: SchedulingStatus,
      to: SchedulingStatus
    ): IO[Seq[ScheduledVideoDownload]] = notImplemented

    override def updateDownloadProgress(
      id: String,
      downloadedBytes: Long,
      timestamp: Instant
    ): IO[Option[ScheduledVideoDownload]] = notImplemented

    override def deleteById(id: String): IO[Int] = notImplemented

    override def search(
      term: Option[String],
      videoUrls: Option[NonEmptyList[Uri]],
      durationRange: RangeValue[FiniteDuration],
      sizeRange: RangeValue[Long],
      pageNumber: Int,
      pageSize: Int,
      sortBy: SortBy,
      order: Order,
      schedulingStatuses: Option[NonEmptyList[SchedulingStatus]],
      videoSites: Option[NonEmptyList[VideoSite]],
      maybeUserId: Option[String]
    ): IO[Seq[ScheduledVideoDownload]] = notImplemented

    override def retryErroredScheduledDownloads(
      maybeUserId: Option[String],
      timestamp: Instant
    ): IO[Seq[ScheduledVideoDownload]] = notImplemented

    override def staleTask(delay: FiniteDuration, timestamp: Instant): IO[Option[ScheduledVideoDownload]] =
      notImplemented

    override def updateTimedOutTasks(
      timeout: FiniteDuration,
      timestamp: Instant
    ): IO[Seq[ScheduledVideoDownload]] = notImplemented

    override def acquireTask(timestamp: Instant): IO[Option[ScheduledVideoDownload]] = notImplemented
  }

  private class StubFileSyncDao(fileSyncs: Ref[IO, Map[String, FileSync]]) extends FileSyncDao[IO] {
    override def insert(fileSync: FileSync): IO[Int] =
      fileSyncs.update(_ + (fileSync.path -> fileSync)).as(1)

    override def findByPath(path: String): IO[Option[FileSync]] =
      fileSyncs.get.map(_.get(path))

    override def complete(path: String, timestamp: Instant): IO[Option[FileSync]] =
      fileSyncs.modify { values =>
        values.get(path) match {
          case None => (values, None)
          case Some(fileSync) =>
            val completed = fileSync.copy(syncedAt = Some(timestamp))
            (values + (path -> completed), Some(completed))
        }
      }

    override def deleteByPath(path: String): IO[Option[FileSync]] =
      fileSyncs.modify(values => (values - path, values.get(path)))
  }

  private class StubVideoDao(videos: Ref[IO, Map[String, Video]]) extends VideoDao[IO] {
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
    ): IO[Seq[Video]] =
      videos.get.map(_.toSeq.sortBy { case (id, _) => id }.map { case (_, video) => video }
        .slice(pageNumber * pageSize, (pageNumber * pageSize) + pageSize))

    override def findByVideoPath(videoPath: String): IO[Option[Video]] =
      videos.get.map(_.values.find(_.fileResource.path == videoPath))

    override def deleteById(videoId: String): IO[Int] =
      videos.modify(values => (values - videoId, if (values.contains(videoId)) 1 else 0))

    override def insert(
      videoMetadataId: String,
      videoFileResourceId: String,
      timestamp: Instant,
      watchTime: FiniteDuration
    ): IO[Int] = notImplemented

    override def incrementWatchTime(videoId: String, finiteDuration: FiniteDuration): IO[Option[FiniteDuration]] =
      notImplemented

    override def findById(videoId: String, maybeUserId: Option[String]): IO[Option[Video]] = notImplemented

    override def findByVideoFileResourceId(fileResourceId: String): IO[Option[Video]] = notImplemented

    override def hasVideoFilePermission(videoFileResourceId: String, userId: String): IO[Boolean] = notImplemented

    override def isVideoFileResourceExist(videoFileResourceId: String): IO[Boolean] = notImplemented

    override val count: IO[Int] = notImplemented

    override val duration: IO[FiniteDuration] = notImplemented

    override val size: IO[Long] = notImplemented

    override val sites: IO[Set[VideoSite]] = notImplemented
  }

  private class StubSnapshotDao(snapshots: Ref[IO, Map[String, Seq[Snapshot]]]) extends SnapshotDao[IO] {
    override def findByVideo(videoId: String, maybeUserId: Option[String]): IO[Seq[Snapshot]] =
      snapshots.get.map(_.getOrElse(videoId, Seq.empty))

    override def deleteByVideo(videoId: String): IO[Int] =
      snapshots.modify(values => (values - videoId, values.get(videoId).fold(0)(_.size)))

    override def insert(snapshot: Snapshot): IO[Int] = notImplemented

    override def hasPermission(snapshotFileResourceId: String, userId: String): IO[Boolean] = notImplemented

    override def isSnapshotFileResource(fileResourceId: String): IO[Boolean] = notImplemented
  }

  private class StubBatchVideoService(
    videos: Ref[IO, Map[String, Video]],
    videoMetadata: Ref[IO, Map[String, VideoMetadata]],
    fileResources: Ref[IO, Map[String, FileResource]],
    deletedVideoIds: Ref[IO, List[(String, Boolean)]]
  ) extends BatchVideoService[IO] {
    override def insert(videoMetadataKey: String, fileResourceKey: String): IO[Video] =
      for {
        maybeMetadata <- videoMetadata.get.map(_.get(videoMetadataKey))
        maybeFileResource <- fileResources.get.map(_.get(fileResourceKey))

        video <- (maybeMetadata, maybeFileResource)
          .mapN((metadata, fileResource) => Video(metadata, fileResource, Timestamp, FiniteDuration(0, "ms")))
          .fold[IO[Video]] {
            IO.raiseError(ResourceNotFoundException(s"Unable to insert video for $videoMetadataKey"))
          }(IO.pure)

        _ <- videos.update(_ + (video.videoMetadata.id -> video))
      } yield video

    override def deleteById(videoId: String, deleteVideoFile: Boolean): IO[Video] =
      deletedVideoIds
        .update(_ :+ (videoId -> deleteVideoFile))
        .productR {
          videos.modify(values => (values - videoId, values.get(videoId)))
        }
        .flatMap {
          _.fold[IO[Video]] {
            IO.raiseError(ResourceNotFoundException(s"Unable to find video with ID = $videoId"))
          }(IO.pure)
        }

    override def incrementWatchTime(videoId: String, duration: FiniteDuration): IO[FiniteDuration] = notImplemented

    override def fetchByVideoFileResourceId(videoFileResourceId: String): IO[Video] = notImplemented

    override def update(videoId: String, size: Long): IO[Video] = notImplemented
  }

  private class StubVideoEnrichmentService(enrichedVideoIds: Ref[IO, List[String]])
      extends VideoEnrichmentService[IO] {
    override val snapshotMediaType: MediaType = MediaType.image.jpeg

    override def videoSnapshots(video: Video): IO[List[Snapshot]] =
      enrichedVideoIds.update(_ :+ video.videoMetadata.id).as(List.empty)

    override def snapshotFileResource(
      videoPath: String,
      snapshotPath: String,
      videoTimestamp: FiniteDuration
    ): IO[FileResource] =
      IO.pure(FileResource(snapshotPath, Timestamp, snapshotPath, MediaType.image.jpeg, 100L))
  }

  private class StubHashingService extends HashingService[IO] {
    override def hash(value: String): IO[String] = IO.pure(s"hash-of-$value")
  }

  private class StubVideoAnalysisService(videoDurationError: Ref[IO, Option[Throwable]])
      extends VideoAnalysisService[IO] {
    override def videoDurationFromPath(videoPath: String): IO[FiniteDuration] =
      videoDurationError.get.flatMap(_.fold[IO[FiniteDuration]](IO.pure(10.minutes))(IO.raiseError))

    override def metadata(uri: Uri): IO[VideoMetadataResult] = notImplemented

    override def analyze(uri: Uri): IO[VideoAnalysisResult] = notImplemented

    override def downloadUri(uri: Uri): IO[Uri] = notImplemented
  }

  private class StubFileTypeDetector(mediaType: MediaType, fileTypeDetectorError: Ref[IO, Option[Throwable]])
      extends FileTypeDetector[IO, String] {
    override def detect(key: String): IO[MediaType] =
      fileTypeDetectorError.get.flatMap(_.fold[IO[MediaType]](IO.pure(mediaType))(IO.raiseError))
  }

  private def notImplemented[A]: IO[A] =
    IO.raiseError(new NotImplementedError("This method is not used by SynchronizationServiceImpl"))
}
