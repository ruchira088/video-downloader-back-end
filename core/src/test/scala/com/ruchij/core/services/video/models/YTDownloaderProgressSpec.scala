package com.ruchij.core.services.video.models

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

class YTDownloaderProgressSpec extends AnyFlatSpec with Matchers {

  "parser" should "parse a String to a YTDownloaderProgress" in {
    YTDownloaderProgress.unapply("[download]  2.2% of ~179.55MiB at 3.43MiB/s ETA 01:51") mustBe
      Some {
        YTDownloaderProgress(
          2.2,
          YTDataSize(179.55, YTDataUnit.MiB),
          YTDataSize(3.43, YTDataUnit.MiB),
          FiniteDuration(111, TimeUnit.SECONDS)
        )
      }

    YTDownloaderProgress.unapply("[download]  10.0% of 1.55GiB at 88.0KiB/s ETA 2:01:10") mustBe
      Some {
        YTDownloaderProgress(
          10,
          YTDataSize(1.55, YTDataUnit.GiB),
          YTDataSize(88, YTDataUnit.KiB),
          FiniteDuration(7270, TimeUnit.SECONDS)
        )
      }

  }

}
