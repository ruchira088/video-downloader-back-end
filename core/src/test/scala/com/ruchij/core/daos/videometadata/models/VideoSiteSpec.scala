package com.ruchij.core.daos.videometadata.models

import com.ruchij.core.daos.videometadata.models.VideoSite.YTDownloaderSite
import org.http4s.implicits.http4sLiteralsSyntax
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class VideoSiteSpec extends AnyFlatSpec with Matchers {

  "fromUri" should "extract the host" in {
    VideoSite.fromUri(uri"https://www.youtube.com/watch?v=aGeFpSL5o9o") mustBe Right(YTDownloaderSite("youtube"))

    VideoSite.fromUri(uri"https://www.xvideos.com/video64863315/random_video") mustBe Right(YTDownloaderSite("xvideos"))
  }

}
