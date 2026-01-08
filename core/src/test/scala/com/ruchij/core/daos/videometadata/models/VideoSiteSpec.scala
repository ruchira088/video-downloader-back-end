package com.ruchij.core.daos.videometadata.models

import cats.effect.IO
import com.ruchij.core.daos.videometadata.models.VideoSite.YTDownloaderSite
import com.ruchij.core.exceptions.ValidationException
import com.ruchij.core.test.IOSupport.runIO
import org.http4s.implicits.http4sLiteralsSyntax
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class VideoSiteSpec extends AnyFlatSpec with Matchers {

  "fromUri" should "extract the host" in {
    VideoSite.fromUri(uri"https://www.youtube.com/watch?v=aGeFpSL5o9o") mustBe Right(YTDownloaderSite("youtube"))

    VideoSite.fromUri(uri"https://www.xvideos.com/video64863315/random_video") mustBe Right(YTDownloaderSite("xvideos"))
  }

  it should "return ValidationException for URI without host" in {
    val result = VideoSite.fromUri(uri"/local/path/video.mp4")

    result.isLeft mustBe true
    result.left.exists(_.isInstanceOf[ValidationException]) mustBe true
  }

  "VideoSite.from" should "parse Local video site" in {
    VideoSite.from("Local") mustBe VideoSite.Local
    VideoSite.from("local") mustBe VideoSite.Local
    VideoSite.from("LOCAL") mustBe VideoSite.Local
  }

  it should "parse CustomVideoSite values" in {
    VideoSite.from("SpankBang") mustBe CustomVideoSite.SpankBang
    VideoSite.from("spankbang") mustBe CustomVideoSite.SpankBang
  }

  it should "create YTDownloaderSite for unknown sites" in {
    val site = VideoSite.from("youtube")
    site mustBe a[VideoSite.YTDownloaderSite]
    site.name mustBe "youtube"
  }

  "VideoSite.Local" should "have name 'Local'" in {
    VideoSite.Local.name mustBe "Local"
  }

  it should "raise error when processUri is called" in runIO {
    VideoSite.Local.processUri[IO](uri"/local/video.mp4").attempt.map { result =>
      result.isLeft mustBe true
      result.left.exists(_.isInstanceOf[ValidationException]) mustBe true
    }
  }

  "YTDownloaderSite" should "store the site name" in {
    val site = VideoSite.YTDownloaderSite("vimeo")
    site.name mustBe "vimeo"
    site.site mustBe "vimeo"
  }

  it should "strip extra query parameters from YouTube URIs" in runIO {
    val site = VideoSite.YTDownloaderSite("youtube")
    val uri = uri"https://www.youtube.com/watch?v=test123&list=playlist456&index=1"

    site.processUri[IO](uri).map { processed =>
      processed.query.params.map(_._1) must contain only "v"
      processed.query.multiParams.get("v") mustBe Some(Seq("test123"))
    }
  }

  it should "pass through non-YouTube URIs unchanged" in runIO {
    val site = VideoSite.YTDownloaderSite("vimeo")
    val uri = uri"https://vimeo.com/123456789?param=value"

    site.processUri[IO](uri).map { processed =>
      processed mustBe uri
    }
  }
}
