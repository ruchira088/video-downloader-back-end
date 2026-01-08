package com.ruchij.core.services.video.models

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class YTDataUnitSpec extends AnyFlatSpec with Matchers {

  "YTDataUnit.unapply" should "parse valid unit strings" in {
    YTDataUnit.unapply("KiB") mustBe Some(YTDataUnit.KiB)
    YTDataUnit.unapply("MiB") mustBe Some(YTDataUnit.MiB)
    YTDataUnit.unapply("GiB") mustBe Some(YTDataUnit.GiB)
  }

  it should "be case insensitive" in {
    YTDataUnit.unapply("kib") mustBe Some(YTDataUnit.KiB)
    YTDataUnit.unapply("mib") mustBe Some(YTDataUnit.MiB)
    YTDataUnit.unapply("gib") mustBe Some(YTDataUnit.GiB)
  }

  it should "return None for invalid units" in {
    YTDataUnit.unapply("KB") mustBe None
    YTDataUnit.unapply("MB") mustBe None
    YTDataUnit.unapply("GB") mustBe None
    YTDataUnit.unapply("invalid") mustBe None
    YTDataUnit.unapply("") mustBe None
  }

  "KiB.toBytes" should "convert to bytes correctly" in {
    YTDataUnit.KiB.toBytes(1) mustBe 1024.0
    YTDataUnit.KiB.toBytes(2) mustBe 2048.0
    YTDataUnit.KiB.toBytes(0.5) mustBe 512.0
  }

  "MiB.toBytes" should "convert to bytes correctly" in {
    YTDataUnit.MiB.toBytes(1) mustBe (1024.0 * 1024.0)
    YTDataUnit.MiB.toBytes(2) mustBe (2048.0 * 1024.0)
  }

  "GiB.toBytes" should "convert to bytes correctly" in {
    YTDataUnit.GiB.toBytes(1) mustBe (1024.0 * 1024.0 * 1024.0)
    YTDataUnit.GiB.toBytes(0.5) mustBe (512.0 * 1024.0 * 1024.0)
  }

  "values" should "contain all YTDataUnit types" in {
    YTDataUnit.values must contain allOf (
      YTDataUnit.KiB,
      YTDataUnit.MiB,
      YTDataUnit.GiB
    )
    YTDataUnit.values.size mustBe 3
  }

  "conversion" should "be consistent across units" in {
    // 1 GiB = 1024 MiB = 1048576 KiB
    YTDataUnit.GiB.toBytes(1) mustBe YTDataUnit.MiB.toBytes(1024)
    YTDataUnit.MiB.toBytes(1) mustBe YTDataUnit.KiB.toBytes(1024)
  }
}
