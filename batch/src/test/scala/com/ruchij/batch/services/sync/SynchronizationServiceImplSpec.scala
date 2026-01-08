package com.ruchij.batch.services.sync

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
}
