package com.ruchij.core.services.video.models

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class YTDataSizeSpec extends AnyFlatSpec with Matchers {

  "YTDataSize.unapply" should "parse valid data size strings" in {
    YTDataSize.unapply("100MiB") mustBe Some(YTDataSize(100, YTDataUnit.MiB))
    YTDataSize.unapply("50.5GiB") mustBe Some(YTDataSize(50.5, YTDataUnit.GiB))
    YTDataSize.unapply("512KiB") mustBe Some(YTDataSize(512, YTDataUnit.KiB))
  }

  it should "parse decimal values" in {
    YTDataSize.unapply("1.5MiB") mustBe Some(YTDataSize(1.5, YTDataUnit.MiB))
    YTDataSize.unapply("0.5GiB") mustBe Some(YTDataSize(0.5, YTDataUnit.GiB))
  }

  it should "return None for invalid input" in {
    YTDataSize.unapply("100MB") mustBe None
    YTDataSize.unapply("invalid") mustBe None
    YTDataSize.unapply("") mustBe None
    YTDataSize.unapply("100") mustBe None
  }

  "bytes" should "correctly convert to bytes" in {
    YTDataSize(1, YTDataUnit.KiB).bytes mustBe 1024.0
    YTDataSize(1, YTDataUnit.MiB).bytes mustBe (1024.0 * 1024.0)
    YTDataSize(1, YTDataUnit.GiB).bytes mustBe (1024.0 * 1024.0 * 1024.0)
  }

  "ytDataSizeOrdering" should "compare data sizes correctly" in {
    val small = YTDataSize(100, YTDataUnit.KiB)
    val medium = YTDataSize(1, YTDataUnit.MiB)
    val large = YTDataSize(1, YTDataUnit.GiB)

    Seq(large, small, medium).sorted mustBe Seq(small, medium, large)
  }

  it should "handle equal sizes in different units" in {
    val kib = YTDataSize(1024, YTDataUnit.KiB)
    val mib = YTDataSize(1, YTDataUnit.MiB)

    YTDataSize.ytDataSizeOrdering.compare(kib, mib) mustBe 0
  }
}
