package com.ruchij.batch.services.sync

import com.ruchij.batch.services.sync.models.FileSyncResult._
import com.ruchij.batch.services.sync.models.SynchronizationResult
import com.ruchij.core.test.data.CoreTestData
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class SynchronizationServiceImplSpec extends AnyFlatSpec with Matchers {

  "videoIdFromVideoFile" should "extract the video ID from file path" in {
    SynchronizationServiceImpl.videoIdFromVideoFile("/video-downloader-back-end/videos/spankbang-88fc6aea-9415482-720p.mp4") mustBe
      Some("spankbang-88fc6aea")

    SynchronizationServiceImpl.videoIdFromVideoFile("videos/youtube-7f31f6b5.mkv") mustBe
      Some("youtube-7f31f6b5")
  }

  it should "extract video ID from various file path formats" in {
    // Windows-style path
    SynchronizationServiceImpl.videoIdFromVideoFile("C:\\videos\\youtube-abc12345.mp4") mustBe
      Some("youtube-abc12345")

    // Unix absolute path
    SynchronizationServiceImpl.videoIdFromVideoFile("/home/user/videos/vimeo-def67890.webm") mustBe
      Some("vimeo-def67890")

    // Just filename
    SynchronizationServiceImpl.videoIdFromVideoFile("local-8df3cff-somefile.mp4") mustBe
      Some("local-8df3cff")
  }

  it should "handle filenames with multiple dashes" in {
    SynchronizationServiceImpl.videoIdFromVideoFile("youtube-7488acd8-title-with-dashes-720p.mp4") mustBe
      Some("youtube-7488acd8")

    SynchronizationServiceImpl.videoIdFromVideoFile("spankbang-65a082c5-10256141-720p.mp4") mustBe
      Some("spankbang-65a082c5")
  }

  it should "return None for invalid file paths" in {
    // No dash separator
    SynchronizationServiceImpl.videoIdFromVideoFile("invalidfilename.mp4") mustBe None

    // Empty string
    SynchronizationServiceImpl.videoIdFromVideoFile("") mustBe None

    // Just the extension
    SynchronizationServiceImpl.videoIdFromVideoFile(".mp4") mustBe None
  }

  it should "handle different video file extensions" in {
    SynchronizationServiceImpl.videoIdFromVideoFile("youtube-12345678.mp4") mustBe Some("youtube-12345678")
    SynchronizationServiceImpl.videoIdFromVideoFile("youtube-12345678.mkv") mustBe Some("youtube-12345678")
    SynchronizationServiceImpl.videoIdFromVideoFile("youtube-12345678.webm") mustBe Some("youtube-12345678")
    SynchronizationServiceImpl.videoIdFromVideoFile("youtube-12345678.avi") mustBe Some("youtube-12345678")
  }

  it should "extract video ID regardless of directory depth" in {
    SynchronizationServiceImpl.videoIdFromVideoFile("/a/b/c/d/e/youtube-abcdefgh.mp4") mustBe
      Some("youtube-abcdefgh")

    SynchronizationServiceImpl.videoIdFromVideoFile("youtube-abcdefgh.mp4") mustBe
      Some("youtube-abcdefgh")
  }

  it should "handle video IDs with alphanumeric characters" in {
    SynchronizationServiceImpl.videoIdFromVideoFile("pornhub-a1b2c3d4.mp4") mustBe Some("pornhub-a1b2c3d4")
    SynchronizationServiceImpl.videoIdFromVideoFile("xvideos-ABCD1234.mkv") mustBe Some("xvideos-ABCD1234")
    SynchronizationServiceImpl.videoIdFromVideoFile("redtube-12ab34cd.webm") mustBe Some("redtube-12ab34cd")
  }

  it should "handle paths with special characters in directory names" in {
    SynchronizationServiceImpl.videoIdFromVideoFile("/home/user/my videos/youtube-abc12345.mp4") mustBe
      Some("youtube-abc12345")

    SynchronizationServiceImpl.videoIdFromVideoFile("/media/external (drive)/youtube-def67890.mkv") mustBe
      Some("youtube-def67890")
  }

  it should "handle mixed path separators" in {
    SynchronizationServiceImpl.videoIdFromVideoFile("C:\\Users\\name/videos\\youtube-mixed123.mp4") mustBe
      Some("youtube-mixed123")
  }

  it should "return None for filenames starting with a dash" in {
    SynchronizationServiceImpl.videoIdFromVideoFile("-invalidstart.mp4") mustBe None
  }

  it should "extract video ID from local video files" in {
    SynchronizationServiceImpl.videoIdFromVideoFile("local-a1b2c3d4.mp4") mustBe Some("local-a1b2c3d4")
    SynchronizationServiceImpl.videoIdFromVideoFile("/videos/local-deadbeef.mkv") mustBe Some("local-deadbeef")
    SynchronizationServiceImpl.videoIdFromVideoFile("local-12345678-extra-info.webm") mustBe Some("local-12345678")
  }

  it should "handle video files with uppercase extensions" in {
    SynchronizationServiceImpl.videoIdFromVideoFile("youtube-abc12345.MP4") mustBe Some("youtube-abc12345")
    SynchronizationServiceImpl.videoIdFromVideoFile("youtube-abc12345.MKV") mustBe Some("youtube-abc12345")
  }

  it should "handle video files without extensions" in {
    SynchronizationServiceImpl.videoIdFromVideoFile("youtube-abc12345") mustBe Some("youtube-abc12345")
  }

  it should "handle paths ending with a slash" in {
    SynchronizationServiceImpl.videoIdFromVideoFile("/videos/youtube-abc12345.mp4/") mustBe Some("youtube-abc12345")
  }

  "SynchronizationResult" should "correctly add results together" in {
    val result1 = SynchronizationResult(
      existingVideoFiles = 10,
      syncedVideos = 5,
      missingVideoFiles = 2,
      videoCountOfSnapshotsUpdated = 3,
      syncErrors = 1,
      ignoredFiles = 20
    )

    val result2 = SynchronizationResult(
      existingVideoFiles = 5,
      syncedVideos = 3,
      missingVideoFiles = 1,
      videoCountOfSnapshotsUpdated = 4,
      syncErrors = 2,
      ignoredFiles = 15
    )

    val combined = result1 + result2

    combined.syncedVideos mustBe 8
    combined.existingVideoFiles mustBe 15
    combined.ignoredFiles mustBe 35
    combined.syncErrors mustBe 3
    combined.missingVideoFiles mustBe 3
    combined.videoCountOfSnapshotsUpdated mustBe 7
  }

  it should "have zero as identity element" in {
    val result = SynchronizationResult(
      existingVideoFiles = 10,
      syncedVideos = 5,
      missingVideoFiles = 2,
      videoCountOfSnapshotsUpdated = 3,
      syncErrors = 1,
      ignoredFiles = 20
    )

    val combinedWithZero = result + SynchronizationResult.Zero

    combinedWithZero mustBe result
  }

  it should "produce a readable pretty print output" in {
    val result = SynchronizationResult(
      existingVideoFiles = 10,
      syncedVideos = 5,
      missingVideoFiles = 2,
      videoCountOfSnapshotsUpdated = 3,
      syncErrors = 1,
      ignoredFiles = 20
    )

    val prettyPrint = result.prettyPrint

    prettyPrint must include("5")
    prettyPrint must include("10")
    prettyPrint must include("20")
    prettyPrint must include("1")
    prettyPrint must include("2")
  }

  "Zero result" should "have all fields set to zero" in {
    val zero = SynchronizationResult.Zero

    zero.syncedVideos mustBe 0
    zero.existingVideoFiles mustBe 0
    zero.ignoredFiles mustBe 0
    zero.syncErrors mustBe 0
    zero.missingVideoFiles mustBe 0
    zero.videoCountOfSnapshotsUpdated mustBe 0
  }

  "SynchronizationResult + FileSyncResult" should "increment syncedVideos for VideoSynced" in {
    val result = SynchronizationResult.Zero + VideoSynced(CoreTestData.YouTubeVideo)

    result.syncedVideos mustBe 1
    result.existingVideoFiles mustBe 0
    result.ignoredFiles mustBe 0
    result.syncErrors mustBe 0
    result.missingVideoFiles mustBe 0
    result.videoCountOfSnapshotsUpdated mustBe 0
  }

  it should "increment existingVideoFiles for ExistingVideo" in {
    val result = SynchronizationResult.Zero + ExistingVideo("/path/to/video.mp4")

    result.existingVideoFiles mustBe 1
    result.syncedVideos mustBe 0
    result.ignoredFiles mustBe 0
    result.syncErrors mustBe 0
    result.missingVideoFiles mustBe 0
    result.videoCountOfSnapshotsUpdated mustBe 0
  }

  it should "increment ignoredFiles for IgnoredFile" in {
    val result = SynchronizationResult.Zero + IgnoredFile("/path/to/file.txt")

    result.ignoredFiles mustBe 1
    result.syncedVideos mustBe 0
    result.existingVideoFiles mustBe 0
    result.syncErrors mustBe 0
    result.missingVideoFiles mustBe 0
    result.videoCountOfSnapshotsUpdated mustBe 0
  }

  it should "increment syncErrors for SyncError" in {
    val result = SynchronizationResult.Zero + SyncError(new RuntimeException("Test error"), "/path/to/video.mp4")

    result.syncErrors mustBe 1
    result.syncedVideos mustBe 0
    result.existingVideoFiles mustBe 0
    result.ignoredFiles mustBe 0
    result.missingVideoFiles mustBe 0
    result.videoCountOfSnapshotsUpdated mustBe 0
  }

  it should "increment missingVideoFiles for MissingVideoFile" in {
    val result = SynchronizationResult.Zero + MissingVideoFile(CoreTestData.SpankBangVideo)

    result.missingVideoFiles mustBe 1
    result.syncedVideos mustBe 0
    result.existingVideoFiles mustBe 0
    result.ignoredFiles mustBe 0
    result.syncErrors mustBe 0
    result.videoCountOfSnapshotsUpdated mustBe 0
  }

  it should "increment videoCountOfSnapshotsUpdated for VideoSnapshotsCreated" in {
    val result = SynchronizationResult.Zero + VideoSnapshotsCreated(CoreTestData.LocalVideo)

    result.videoCountOfSnapshotsUpdated mustBe 1
    result.syncedVideos mustBe 0
    result.existingVideoFiles mustBe 0
    result.ignoredFiles mustBe 0
    result.syncErrors mustBe 0
    result.missingVideoFiles mustBe 0
  }

  it should "accumulate multiple FileSyncResults" in {
    val result = SynchronizationResult.Zero +
      VideoSynced(CoreTestData.YouTubeVideo) +
      VideoSynced(CoreTestData.SpankBangVideo) +
      ExistingVideo("/path/to/existing1.mp4") +
      ExistingVideo("/path/to/existing2.mp4") +
      ExistingVideo("/path/to/existing3.mp4") +
      IgnoredFile("/path/to/file.txt") +
      SyncError(new RuntimeException("Error"), "/path/error.mp4") +
      MissingVideoFile(CoreTestData.LocalVideo) +
      VideoSnapshotsCreated(CoreTestData.YouTubeVideo) +
      VideoSnapshotsCreated(CoreTestData.SpankBangVideo)

    result.syncedVideos mustBe 2
    result.existingVideoFiles mustBe 3
    result.ignoredFiles mustBe 1
    result.syncErrors mustBe 1
    result.missingVideoFiles mustBe 1
    result.videoCountOfSnapshotsUpdated mustBe 2
  }

  "FileSyncResult.ExistingVideo" should "return the file path" in {
    val existingVideo = ExistingVideo("/path/to/video.mp4")
    existingVideo.filePath mustBe "/path/to/video.mp4"
  }

  "FileSyncResult.VideoSynced" should "return the video file path" in {
    val videoSynced = VideoSynced(CoreTestData.YouTubeVideo)
    videoSynced.filePath mustBe CoreTestData.YouTubeVideo.fileResource.path
  }

  "FileSyncResult.VideoSnapshotsCreated" should "return the video file path" in {
    val snapshotsCreated = VideoSnapshotsCreated(CoreTestData.SpankBangVideo)
    snapshotsCreated.filePath mustBe CoreTestData.SpankBangVideo.fileResource.path
  }

  "FileSyncResult.MissingVideoFile" should "return the video file path" in {
    val missingVideo = MissingVideoFile(CoreTestData.LocalVideo)
    missingVideo.filePath mustBe CoreTestData.LocalVideo.fileResource.path
  }

  "FileSyncResult.SyncError" should "contain the throwable and file path" in {
    val exception = new RuntimeException("Test exception")
    val syncError = SyncError(exception, "/path/to/failed.mp4")

    syncError.throwable mustBe exception
    syncError.filePath mustBe "/path/to/failed.mp4"
  }

  "FileSyncResult.IgnoredFile" should "return the file path" in {
    val ignoredFile = IgnoredFile("/path/to/ignored.txt")
    ignoredFile.filePath mustBe "/path/to/ignored.txt"
  }
}
