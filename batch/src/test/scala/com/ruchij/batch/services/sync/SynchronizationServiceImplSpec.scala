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

}
