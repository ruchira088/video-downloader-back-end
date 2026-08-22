package com.ruchij.core.daos.videometadata.models

import cats.effect.IO
import com.ruchij.core.daos.videometadata.models.CustomVideoSite._
import com.ruchij.core.exceptions.JSoupException.NoMatchingElementsFoundException
import com.ruchij.core.services.renderer.SpaSiteRenderer
import com.ruchij.core.test.IOSupport.{IOWrapper, runIO}
import org.http4s.Uri
import org.http4s.implicits.http4sLiteralsSyntax
import org.jsoup.Jsoup
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.duration.DurationInt
import scala.io.Source
import scala.util.Using

/**
  * Exercises the JSoup selectors of every [[CustomVideoSite]] against recorded HTML fixtures.
  *
  * The selectors are the part of the scraping layer that breaks whenever a site changes its markup,
  * and they were previously only covered by the live-network tests in `VideoAnalysisServiceImplSpec`
  * (all of which are `ignore`d). Fixtures keep that coverage deterministic and offline.
  */
class CustomVideoSiteSelectorsSpec extends AnyFlatSpec with Matchers {

  "FreshPorno selectors" should "extract the video metadata from the web page" in runIO {
    val webPage = fixture("freshporno.html", uri"https://freshporno.net/videos/1234/sample/")

    for {
      title <- FreshPorno.title[IO].run(webPage)
      thumbnail <- FreshPorno.thumbnailUri[IO].run(webPage)
      duration <- FreshPorno.duration[IO].run(webPage)
      downloadUri <- FreshPorno.downloadUri[IO].run(webPage)
    } yield {
      title mustBe "FreshPorno sample video"
      thumbnail mustBe uri"https://cdn.freshporno.net/thumbs/sample.jpg"
      duration mustBe 12.minutes + 34.seconds
      downloadUri mustBe uri"https://cdn.freshporno.net/videos/sample-720p.mp4"
    }
  }

  it should "fall back to the download button when the download list is absent" in runIO {
    val webPage =
      fixture("freshporno-download-button.html", uri"https://freshporno.net/videos/1234/sample/")

    FreshPorno.downloadUri[IO].run(webPage).map { downloadUri =>
      downloadUri mustBe uri"https://cdn.freshporno.net/videos/sample-fallback.mp4"
    }
  }

  "PornOne selectors" should "extract the video metadata from the embedded JSON metadata" in runIO {
    val webPage = fixture("pornone.html", uri"https://pornone.com/video/277968339/")

    for {
      title <- PornOne.title[IO].run(webPage)
      thumbnail <- PornOne.thumbnailUri[IO].run(webPage)
      duration <- PornOne.duration[IO].run(webPage)
      downloadUri <- PornOne.downloadUri[IO].run(webPage)
    } yield {
      title mustBe "PornOne sample video"
      thumbnail mustBe uri"https://th-eu4.pornone.com/t/39/sample.jpg"
      duration mustBe 34.minutes + 19.seconds

      // The `source` src is protocol-relative and is resolved against the page scheme
      downloadUri mustBe uri"https://cdn.pornone.com/videos/sample.mp4"
    }
  }

  "SpankBang selectors" should "extract the video metadata from the web page" in runIO {
    val webPage = fixture("spankbang.html", uri"https://spankbang.com/abcde/video/sample")

    for {
      title <- SpankBang.title[IO].run(webPage)
      thumbnail <- SpankBang.thumbnailUri[IO].run(webPage)
      duration <- SpankBang.duration[IO].run(webPage)
      downloadUri <- SpankBang.downloadUri[IO].run(webPage)
    } yield {
      title mustBe "SpankBang sample video"
      thumbnail mustBe uri"https://cdn.spankbang.com/thumbs/sample.jpg"

      // hh:mm:ss durations are parsed by the three term format
      duration mustBe 1.hour + 2.minutes + 3.seconds
      downloadUri mustBe uri"https://cdn.spankbang.com/videos/sample.mp4"
    }
  }

  "XFreeHD selectors" should "extract the video metadata and prefer the HD source" in runIO {
    val webPage = fixture("xfreehd.html", uri"https://www.xfreehd.com/video/1234/sample")

    for {
      title <- XFreeHD.title[IO].run(webPage)
      thumbnail <- XFreeHD.thumbnailUri[IO].run(webPage)
      duration <- XFreeHD.duration[IO].run(webPage)
      downloadUri <- XFreeHD.downloadUri[IO].run(webPage)
    } yield {
      title mustBe "XFreeHD sample video"
      thumbnail mustBe uri"https://www.xfreehd.com/thumbs/sample.jpg"

      // The duration is the `time` query parameter of the WebVTT track, in seconds
      duration mustBe 15.minutes + 30.seconds
      downloadUri mustBe uri"https://www.xfreehd.com/videos/sample-hd.mp4"
    }
  }

  it should "fall back to the last source when no source is titled HD" in runIO {
    val webPage = fixture("xfreehd-no-hd-source.html", uri"https://www.xfreehd.com/video/1234/sample")

    XFreeHD.downloadUri[IO].run(webPage).map { downloadUri =>
      downloadUri mustBe uri"https://www.xfreehd.com/videos/sample-480p.mp4"
    }
  }

  it should "default the duration to zero when the WebVTT track has no time parameter" in runIO {
    val webPage = fixture("xfreehd-no-hd-source.html", uri"https://www.xfreehd.com/video/1234/sample")

    XFreeHD.duration[IO].run(webPage).map { duration =>
      duration mustBe 0.seconds
    }
  }

  "SxyPrn selectors" should "extract the video metadata from the web page" in runIO {
    val webPage = fixture("sxyprn.html", uri"https://sxyprn.com/post/661d0cec4b19c.html")

    for {
      title <- SxyPrn.title[IO].run(webPage)
      thumbnail <- SxyPrn.thumbnailUri[IO].run(webPage)
      duration <- SxyPrn.duration[IO].run(webPage)
    } yield {
      title mustBe "SxyPrn sample video"

      // The poster is a root relative path, resolved against the page host
      thumbnail mustBe uri"https://sxyprn.com/cdn/thumbs/sample.jpg"
      duration mustBe 31.minutes + 26.seconds
    }
  }

  it should "render the download URI with the SPA site renderer" in runIO {
    val uri = uri"https://sxyprn.com/post/661d0cec4b19c.html"
    val spaSiteRenderer =
      stubSpaSiteRenderer("""{"videoUrl": "https://cdn.sxyprn.com/videos/sample.mp4"}""")

    SxyPrn.downloadUri[IO](uri, spaSiteRenderer).map { downloadUri =>
      downloadUri mustBe uri"https://cdn.sxyprn.com/videos/sample.mp4"
    }
  }

  CustomVideoSite.values.collect { case txxxNetwork: TxxxNetwork => txxxNetwork }.foreach { txxxNetwork =>
    s"${txxxNetwork.name} selectors" should "extract the video metadata from the embedded JSON metadata" in runIO {
      val webPage = fixture("txxx-network.html", pageUri(txxxNetwork))

      for {
        title <- txxxNetwork.title[IO].run(webPage)
        thumbnail <- txxxNetwork.thumbnailUri[IO].run(webPage)
        duration <- txxxNetwork.duration[IO].run(webPage)
      } yield {
        title mustBe "TXXX network sample video"

        // The JSON metadata wins over the `.jw-preview` and `.jw-text-duration` fall backs
        thumbnail mustBe uri"https://thumb.txxx.com/sample.jpg"
        duration mustBe 21.minutes + 37.seconds
      }
    }

    it should "fall back to the player elements when the JSON metadata is incomplete" in runIO {
      val webPage = fixture("txxx-network-incomplete-metadata.html", pageUri(txxxNetwork))

      for {
        thumbnail <- txxxNetwork.thumbnailUri[IO].run(webPage)
        duration <- txxxNetwork.duration[IO].run(webPage)
      } yield {
        thumbnail mustBe uri"https://thumb.txxx.com/fallback.jpg"
        duration mustBe 10.minutes + 11.seconds
      }
    }

    it should "fall back to the placeholder image when the preview style has no background image" in runIO {
      val webPage = fixture("txxx-network-unparsable-preview.html", pageUri(txxxNetwork))

      txxxNetwork.thumbnailUri[IO].run(webPage).map { thumbnail =>
        thumbnail mustBe
          uri"https://s3.ap-southeast-2.amazonaws.com/assets.video-downloader.ruchij.com/video-placeholder.png"
      }
    }

    it should "strip the f query parameter from the rendered download URI" in runIO {
      val spaSiteRenderer =
        stubSpaSiteRenderer(
          s"""{"videoUrl": "https://${txxxNetwork.hostname}/get_file/sample.mp4?f=signature&br=3200"}"""
        )

      txxxNetwork.downloadUri[IO](pageUri(txxxNetwork), spaSiteRenderer).map { downloadUri =>
        downloadUri.query.params.get("f") mustBe None
        downloadUri.query.params.get("br") mustBe Some("3200")
      }
    }
  }

  "A selector" should "raise a NoMatchingElementsFoundException when the markup no longer matches" in runIO {
    val webPage = fixture("spankbang.html", uri"https://spankbang.com/abcde/video/sample")

    // A SpankBang page put through the XFreeHD selectors stands in for a site changing its markup
    XFreeHD.title[IO].run(webPage).error.map { throwable =>
      throwable mustBe a[NoMatchingElementsFoundException]
    }
  }

  private def pageUri(customVideoSite: CustomVideoSite): Uri =
    Uri.unsafeFromString(s"https://${customVideoSite.hostname}/videos/1234/sample")

  private def fixture(fileName: String, uri: Uri): WebPage =
    WebPage(uri, Jsoup.parse(resourceAsString(s"video-sites/$fileName"), uri.renderString))

  private def resourceAsString(resourcePath: String): String =
    Using.resource(getClass.getClassLoader.getResourceAsStream(resourcePath)) { inputStream =>
      Source.fromInputStream(inputStream, "UTF-8").mkString
    }

  private def stubSpaSiteRenderer(javaScriptOutput: String): SpaSiteRenderer[IO] =
    new SpaSiteRenderer[IO] {
      override def render(uri: Uri, readyCssSelectors: Seq[String]): IO[String] =
        IO.raiseError(new NotImplementedError("render is not used by these tests"))

      override def executeJavaScript(uri: Uri, readyCssSelectors: Seq[String], script: String): IO[String] =
        IO.pure(javaScriptOutput)
    }
}
