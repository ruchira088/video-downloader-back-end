package com.ruchij.core.daos.videometadata.models

import cats.effect.IO
import cats.implicits._
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

  it should "handle single segment hostname" in {
    val result = VideoSite.fromUri(uri"http://localhost/video.mp4")

    result.isRight mustBe true
    result.map(_.name) mustBe Right("localhost")
  }

  it should "handle IP address hostname by using second segment" in {
    // Note: For IP addresses like 192.168.1.1, the logic extracts the second-to-last segment
    val result = VideoSite.fromUri(uri"http://192.168.1.1/video.mp4")

    result.isRight mustBe true
    result.map(_.name) mustBe Right("1")
  }

  it should "recognize CustomVideoSite URIs" in {
    val result = VideoSite.fromUri(uri"https://www.spankbang.com/video/123")

    result.isRight mustBe true
    result mustBe Right(CustomVideoSite.SpankBang)
  }

  it should "recognize PornOne URIs" in {
    val result = VideoSite.fromUri(uri"https://pornone.com/video/456")

    result.isRight mustBe true
    result mustBe Right(CustomVideoSite.PornOne)
  }

  "VideoSite.processUri" should "process URI based on video site" in runIO {
    VideoSite.processUri[IO](uri"https://www.youtube.com/watch?v=test&list=abc").map { processed =>
      processed.query.params.map(_._1) must contain only "v"
    }
  }

  it should "return error for URI without host" in runIO {
    VideoSite.processUri[IO](uri"/local/video.mp4").attempt.map { result =>
      result.isLeft mustBe true
      result.left.exists(_.isInstanceOf[ValidationException]) mustBe true
    }
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

  // CustomVideoSite tests
  "CustomVideoSite.test" should "match URIs by hostname" in {
    val spankBangUri = uri"https://www.spankbang.com/video/123"
    val otherUri = uri"https://www.youtube.com/watch?v=test"

    CustomVideoSite.SpankBang.test(spankBangUri) mustBe true
    CustomVideoSite.SpankBang.test(otherUri) mustBe false
  }

  it should "match case-insensitively" in {
    val upperCaseUri = uri"https://SPANKBANG.COM/video/123"
    CustomVideoSite.SpankBang.test(upperCaseUri) mustBe true
  }

  "CustomVideoSite.PornOne" should "strip query parameters in processUri" in runIO {
    val uri = uri"https://pornone.com/video/123?source=referral"

    CustomVideoSite.PornOne.processUri[IO](uri).map { processed =>
      processed.query mustBe org.http4s.Query.empty
    }
  }

  "CustomVideoSite.values" should "contain all CustomVideoSite instances" in {
    val values = CustomVideoSite.values

    values must contain(CustomVideoSite.SpankBang)
    values must contain(CustomVideoSite.PornOne)
    values must contain(CustomVideoSite.FreshPorno)
    values must contain(CustomVideoSite.XFreeHD)
    values must contain(CustomVideoSite.SxyPrn)
    values must contain(CustomVideoSite.TXXX)
    values must contain(CustomVideoSite.UPornia)
    values must contain(CustomVideoSite.HClips)
    values must contain(CustomVideoSite.HotMovs)
    values must contain(CustomVideoSite.HdZog)
  }

  "CustomVideoSite hostnames" should "be correctly defined" in {
    CustomVideoSite.SpankBang.hostname mustBe "spankbang.com"
    CustomVideoSite.PornOne.hostname mustBe "pornone.com"
    CustomVideoSite.FreshPorno.hostname mustBe "freshporno.net"
    CustomVideoSite.XFreeHD.hostname mustBe "www.xfreehd.com"
    CustomVideoSite.SxyPrn.hostname mustBe "sxyprn.com"
    CustomVideoSite.TXXX.hostname mustBe "txxx.com"
    CustomVideoSite.UPornia.hostname mustBe "upornia.com"
    CustomVideoSite.HClips.hostname mustBe "hclips.com"
    CustomVideoSite.HotMovs.hostname mustBe "hotmovs.com"
    CustomVideoSite.HdZog.hostname mustBe "hdzog.com"
  }

  "CustomVideoSite names" should "match entry names" in {
    CustomVideoSite.SpankBang.name mustBe "SpankBang"
    CustomVideoSite.PornOne.name mustBe "PornOne"
    CustomVideoSite.TXXX.name mustBe "TXXX"
    CustomVideoSite.SxyPrn.name mustBe "SxyPrn"
  }

  "SpaCustomVideoSite readyCssSelectors" should "be defined for SxyPrn" in {
    CustomVideoSite.SxyPrn.readyCssSelectors mustBe List("#player_el[src]")
  }

  it should "be defined for TxxxNetwork sites" in {
    CustomVideoSite.TXXX.readyCssSelectors must contain(".jw-preview[style]")
    CustomVideoSite.TXXX.readyCssSelectors must contain(".jw-text-duration")
    CustomVideoSite.TXXX.readyCssSelectors must contain("video.jw-video[src]")
    CustomVideoSite.TXXX.readyCssSelectors must contain("script[type='application/ld+json']")
  }

  "CustomVideoSite.SpankBang" should "parse title from HTML" in runIO {
    import org.jsoup.Jsoup
    val html = """
      <html>
        <div id="video">
          <div class="left">
            <h1 title="Sample Video Title">Sample Video Title</h1>
          </div>
        </div>
      </html>
    """
    val document = Jsoup.parse(html)
    val webPage = WebPage(uri"https://spankbang.com/123/video", document)

    CustomVideoSite.SpankBang.title[IO].run(webPage).map { title =>
      title mustBe "Sample Video Title"
    }
  }

  it should "parse thumbnail URI from HTML" in runIO {
    import org.jsoup.Jsoup
    val html = """
      <html>
        <div id="player_wrapper_outer">
          <div class="play_cover">
            <img class="player_thumb" src="https://cdn.spankbang.com/thumb.jpg" />
          </div>
        </div>
      </html>
    """
    val document = Jsoup.parse(html)
    val webPage = WebPage(uri"https://spankbang.com/123/video", document)

    CustomVideoSite.SpankBang.thumbnailUri[IO].run(webPage).map { thumbUri =>
      thumbUri.toString must include("cdn.spankbang.com")
    }
  }

  it should "parse duration from HTML" in runIO {
    import org.jsoup.Jsoup
    val html = """
      <html>
        <div id="player_wrapper_outer">
          <span class="hd-time">
            <span class="i-length">12:34</span>
          </span>
        </div>
      </html>
    """
    val document = Jsoup.parse(html)
    val webPage = WebPage(uri"https://spankbang.com/123/video", document)

    CustomVideoSite.SpankBang.duration[IO].run(webPage).map { duration =>
      duration.toMinutes mustBe 12
      duration.toSeconds mustBe 754
    }
  }

  it should "parse downloadUri from HTML" in runIO {
    import org.jsoup.Jsoup
    val html = """
      <html>
        <div id="video_container">
          <source src="https://cdn.spankbang.com/video.mp4" />
        </div>
      </html>
    """
    val document = Jsoup.parse(html)
    val webPage = WebPage(uri"https://spankbang.com/123/video", document)

    CustomVideoSite.SpankBang.downloadUri[IO].run(webPage).map { downloadUri =>
      downloadUri.toString mustBe "https://cdn.spankbang.com/video.mp4"
    }
  }

  it should "parse duration in HH:MM:SS format" in runIO {
    import org.jsoup.Jsoup
    val html = """
      <html>
        <div id="player_wrapper_outer">
          <span class="hd-time">
            <span class="i-length">1:23:45</span>
          </span>
        </div>
      </html>
    """
    val document = Jsoup.parse(html)
    val webPage = WebPage(uri"https://spankbang.com/123/video", document)

    CustomVideoSite.SpankBang.duration[IO].run(webPage).map { duration =>
      duration.toHours mustBe 1
      duration.toMinutes mustBe 83
      duration.toSeconds mustBe 5025
    }
  }

  "CustomVideoSite.FreshPorno" should "have correct hostname" in {
    CustomVideoSite.FreshPorno.hostname mustBe "freshporno.net"
  }

  it should "parse title from HTML" in runIO {
    import org.jsoup.Jsoup
    val html = """
      <html>
        <div class="video-info">
          <div class="title-holder">
            <h1>Fresh Video Title</h1>
          </div>
        </div>
      </html>
    """
    val document = Jsoup.parse(html)
    val webPage = WebPage(uri"https://freshporno.net/video/123", document)

    CustomVideoSite.FreshPorno.title[IO].run(webPage).map { title =>
      title mustBe "Fresh Video Title"
    }
  }

  it should "parse duration from time element" in runIO {
    import org.jsoup.Jsoup
    val html = """
      <html>
        <time>5:30</time>
      </html>
    """
    val document = Jsoup.parse(html)
    val webPage = WebPage(uri"https://freshporno.net/video/123", document)

    CustomVideoSite.FreshPorno.duration[IO].run(webPage).map { duration =>
      duration.toSeconds mustBe 330
    }
  }

  "CustomVideoSite.XFreeHD" should "have correct hostname" in {
    CustomVideoSite.XFreeHD.hostname mustBe "www.xfreehd.com"
  }

  it should "parse title from HTML" in runIO {
    import org.jsoup.Jsoup
    val html = """
      <html>
        <h1 class="big-title-truncate">XFreeHD Video Title</h1>
      </html>
    """
    val document = Jsoup.parse(html)
    val webPage = WebPage(uri"https://www.xfreehd.com/video/123", document)

    CustomVideoSite.XFreeHD.title[IO].run(webPage).map { title =>
      title mustBe "XFreeHD Video Title"
    }
  }

  it should "parse duration from VTT URL time param" in runIO {
    import org.jsoup.Jsoup
    val html = """
      <html>
        <video id="hdPlayer" data-vtt="https://cdn.xfreehd.com/vtt?time=600"></video>
      </html>
    """
    val document = Jsoup.parse(html)
    val webPage = WebPage(uri"https://www.xfreehd.com/video/123", document)

    CustomVideoSite.XFreeHD.duration[IO].run(webPage).map { duration =>
      duration.toSeconds mustBe 600
      duration.toMinutes mustBe 10
    }
  }

  it should "return 0 duration when time param is missing" in runIO {
    import org.jsoup.Jsoup
    val html = """
      <html>
        <video id="hdPlayer" data-vtt="https://cdn.xfreehd.com/vtt"></video>
      </html>
    """
    val document = Jsoup.parse(html)
    val webPage = WebPage(uri"https://www.xfreehd.com/video/123", document)

    CustomVideoSite.XFreeHD.duration[IO].run(webPage).map { duration =>
      duration.toSeconds mustBe 0
    }
  }

  "Duration parsing" should "fail for invalid duration format" in runIO {
    import org.jsoup.Jsoup
    val html = """
      <html>
        <div id="player_wrapper_outer">
          <span class="hd-time">
            <span class="i-length">invalid-duration</span>
          </span>
        </div>
      </html>
    """
    val document = Jsoup.parse(html)
    val webPage = WebPage(uri"https://spankbang.com/123/video", document)

    CustomVideoSite.SpankBang.duration[IO].run(webPage).attempt.map { result =>
      result.isLeft mustBe true
      result.left.exists(_.getMessage.contains("Unable to parse")) mustBe true
    }
  }

  // TxxxNetwork fallback path tests
  "TxxxNetwork.thumbnailUri" should "use fallback when metadata has no thumbnailUrl" in runIO {
    import org.jsoup.Jsoup
    val html = """
      <html>
        <script type="application/ld+json">{"name": "Test Video", "thumbnailUrl": null, "duration": null}</script>
        <div class="jw-preview" style="background-image: url(&quot;https://cdn.example.com/thumb.jpg&quot;);"></div>
      </html>
    """
    val document = Jsoup.parse(html)
    val webPage = WebPage(uri"https://txxx.com/video/123", document)

    CustomVideoSite.TXXX.thumbnailUri[IO].run(webPage).map { thumbUri =>
      thumbUri.toString mustBe "https://cdn.example.com/thumb.jpg"
    }
  }

  it should "return placeholder URL when style doesn't match thumbnail pattern" in runIO {
    import org.jsoup.Jsoup
    val html = """
      <html>
        <script type="application/ld+json">{"name": "Test Video", "thumbnailUrl": null, "duration": null}</script>
        <div class="jw-preview" style="display: none;"></div>
      </html>
    """
    val document = Jsoup.parse(html)
    val webPage = WebPage(uri"https://txxx.com/video/123", document)

    CustomVideoSite.TXXX.thumbnailUri[IO].run(webPage).map { thumbUri =>
      thumbUri.toString must include("video-placeholder.png")
    }
  }

  "TxxxNetwork.duration" should "use fallback when metadata has no duration" in runIO {
    import org.jsoup.Jsoup
    val html = """
      <html>
        <script type="application/ld+json">{"name": "Test Video", "thumbnailUrl": null, "duration": null}</script>
        <div class="jw-text-duration">15:30</div>
      </html>
    """
    val document = Jsoup.parse(html)
    val webPage = WebPage(uri"https://txxx.com/video/123", document)

    CustomVideoSite.TXXX.duration[IO].run(webPage).map { duration =>
      duration.toSeconds mustBe 930
    }
  }

  "TxxxNetwork.title" should "parse title from metadata" in runIO {
    import org.jsoup.Jsoup
    val html = """
      <html>
        <script type="application/ld+json">{"name": "TXXX Test Video", "thumbnailUrl": null, "duration": null}</script>
      </html>
    """
    val document = Jsoup.parse(html)
    val webPage = WebPage(uri"https://txxx.com/video/123", document)

    CustomVideoSite.TXXX.title[IO].run(webPage).map { title =>
      title mustBe "TXXX Test Video"
    }
  }

  it should "work for all TxxxNetwork sites" in runIO {
    import org.jsoup.Jsoup
    val html = """
      <html>
        <script type="application/ld+json">{"name": "Network Video", "thumbnailUrl": null, "duration": null}</script>
      </html>
    """
    val document = Jsoup.parse(html)

    val sites = List(
      (CustomVideoSite.TXXX, uri"https://txxx.com/video/123"),
      (CustomVideoSite.UPornia, uri"https://upornia.com/video/123"),
      (CustomVideoSite.HClips, uri"https://hclips.com/video/123"),
      (CustomVideoSite.HotMovs, uri"https://hotmovs.com/video/123"),
      (CustomVideoSite.HdZog, uri"https://hdzog.com/video/123")
    )

    sites.traverse { case (site, siteUri) =>
      val webPage = WebPage(siteUri, document)
      site.title[IO].run(webPage).map { title =>
        title mustBe "Network Video"
      }
    }.map(_ => succeed)
  }
}
