package com.ruchij.core.daos.videometadata.models

import cats.effect.IO
import com.ruchij.core.test.IOSupport.runIO
import org.http4s.Uri
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class CustomVideoSiteSpec extends AnyFlatSpec with Matchers {

  "CustomVideoSite values" should "contain all expected sites" in {
    val sites = CustomVideoSite.values

    sites must contain(CustomVideoSite.SpankBang)
    sites must contain(CustomVideoSite.PornOne)
    sites must contain(CustomVideoSite.FreshPorno)
    sites must contain(CustomVideoSite.XFreeHD)
    sites must contain(CustomVideoSite.SxyPrn)
    sites must contain(CustomVideoSite.TXXX)
    sites must contain(CustomVideoSite.UPornia)
    sites must contain(CustomVideoSite.HClips)
    sites must contain(CustomVideoSite.HotMovs)
    sites must contain(CustomVideoSite.HdZog)
  }

  "SpankBang" should "have correct hostname" in {
    CustomVideoSite.SpankBang.hostname mustBe "spankbang.com"
  }

  it should "have correct name" in {
    CustomVideoSite.SpankBang.name mustBe "SpankBang"
  }

  it should "test matching URI" in {
    val uri = Uri.unsafeFromString("https://spankbang.com/video/123")
    CustomVideoSite.SpankBang.test(uri) mustBe true
  }

  it should "reject non-matching URI" in {
    val uri = Uri.unsafeFromString("https://youtube.com/watch?v=123")
    CustomVideoSite.SpankBang.test(uri) mustBe false
  }

  "PornOne" should "have correct hostname" in {
    CustomVideoSite.PornOne.hostname mustBe "pornone.com"
  }

  it should "test matching URI" in {
    val uri = Uri.unsafeFromString("https://www.pornone.com/video/123")
    CustomVideoSite.PornOne.test(uri) mustBe true
  }

  it should "process URI by removing query params" in runIO {
    val uri = Uri.unsafeFromString("https://pornone.com/video/123?source=abc&ref=xyz")

    CustomVideoSite.PornOne.processUri[IO](uri).map { processedUri =>
      processedUri.query.params mustBe empty
      processedUri.path.renderString mustBe "/video/123"
    }
  }

  "FreshPorno" should "have correct hostname" in {
    CustomVideoSite.FreshPorno.hostname mustBe "freshporno.net"
  }

  it should "test matching URI" in {
    val uri = Uri.unsafeFromString("https://freshporno.net/video")
    CustomVideoSite.FreshPorno.test(uri) mustBe true
  }

  "XFreeHD" should "have correct hostname" in {
    CustomVideoSite.XFreeHD.hostname mustBe "www.xfreehd.com"
  }

  it should "test matching URI" in {
    val uri = Uri.unsafeFromString("https://www.xfreehd.com/video/123")
    CustomVideoSite.XFreeHD.test(uri) mustBe true
  }

  "SxyPrn" should "have correct hostname" in {
    CustomVideoSite.SxyPrn.hostname mustBe "sxyprn.com"
  }

  it should "have correct ready CSS selectors" in {
    CustomVideoSite.SxyPrn.readyCssSelectors mustBe List("#player_el[src]")
  }

  "TXXX" should "have correct hostname" in {
    CustomVideoSite.TXXX.hostname mustBe "txxx.com"
  }

  it should "have correct ready CSS selectors" in {
    CustomVideoSite.TXXX.readyCssSelectors must contain(".jw-preview[style]")
    CustomVideoSite.TXXX.readyCssSelectors must contain(".jw-text-duration")
  }

  "UPornia" should "have correct hostname" in {
    CustomVideoSite.UPornia.hostname mustBe "upornia.com"
  }

  "HClips" should "have correct hostname" in {
    CustomVideoSite.HClips.hostname mustBe "hclips.com"
  }

  "HotMovs" should "have correct hostname" in {
    CustomVideoSite.HotMovs.hostname mustBe "hotmovs.com"
  }

  "HdZog" should "have correct hostname" in {
    CustomVideoSite.HdZog.hostname mustBe "hdzog.com"
  }

  "test" should "be case insensitive for hostname matching" in {
    val uri = Uri.unsafeFromString("https://SPANKBANG.COM/video/123")
    CustomVideoSite.SpankBang.test(uri) mustBe true
  }

  it should "match subdomain URIs" in {
    val uri = Uri.unsafeFromString("https://www.spankbang.com/video/123")
    CustomVideoSite.SpankBang.test(uri) mustBe true
  }

  it should "reject URIs without host" in {
    val uri = Uri.unsafeFromString("/video/123")
    CustomVideoSite.SpankBang.test(uri) mustBe false
  }

  "processUri" should "return URI unchanged by default" in runIO {
    val uri = Uri.unsafeFromString("https://spankbang.com/video/123?ref=abc")

    CustomVideoSite.SpankBang.processUri[IO](uri).map { processedUri =>
      processedUri mustBe uri
    }
  }

  "all CustomVideoSites" should "have non-empty hostname" in {
    CustomVideoSite.values.foreach { site =>
      site.hostname must not be empty
    }
  }

  it should "have valid name" in {
    CustomVideoSite.values.foreach { site =>
      site.name must not be empty
      site.name mustBe site.entryName
    }
  }

  "TxxxNetwork sites" should "share the same ready CSS selectors" in {
    val txxx = CustomVideoSite.TXXX.readyCssSelectors
    val upornia = CustomVideoSite.UPornia.readyCssSelectors
    val hclips = CustomVideoSite.HClips.readyCssSelectors
    val hotmovs = CustomVideoSite.HotMovs.readyCssSelectors
    val hdzog = CustomVideoSite.HdZog.readyCssSelectors

    txxx mustBe upornia
    upornia mustBe hclips
    hclips mustBe hotmovs
    hotmovs mustBe hdzog
  }
}
