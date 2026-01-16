package com.ruchij.batch.utils

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class ConstantsSpec extends AnyFlatSpec with Matchers {

  "VideoFileExtensions" should "contain common video file extensions" in {
    val extensions = Constants.VideoFileExtensions

    extensions must contain("mp4")
    extensions must contain("webm")
    extensions must contain("mkv")
    extensions must contain("avi")
    extensions must contain("mov")
    extensions must contain("wmv")
  }

  it should "contain the custom 'vid' extension" in {
    Constants.VideoFileExtensions must contain("vid")
  }

  it should "be a non-empty list" in {
    Constants.VideoFileExtensions must not be empty
  }

  it should "contain all MediaType.video extensions" in {
    import org.http4s.MediaType

    val standardExtensions = MediaType.video.all.flatMap(_.fileExtensions)
    standardExtensions.foreach { ext =>
      Constants.VideoFileExtensions must contain(ext)
    }
  }

  it should "be evaluated lazily" in {
    // This just tests that accessing it doesn't throw
    noException should be thrownBy Constants.VideoFileExtensions
  }
}
