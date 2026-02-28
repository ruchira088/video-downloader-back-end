package com.ruchij.api.services.asset.models

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class AssetTypeSpec extends AnyFlatSpec with Matchers {

  "AssetType" should "contain all expected values" in {
    AssetType.values must contain allOf (
      AssetType.Video,
      AssetType.Thumbnail,
      AssetType.Snapshot,
      AssetType.AlbumArt
    )
  }

  it should "have exactly 4 values" in {
    AssetType.values.size mustBe 4
  }

  it should "find values by name" in {
    AssetType.withName("Video") mustBe AssetType.Video
    AssetType.withName("Thumbnail") mustBe AssetType.Thumbnail
    AssetType.withName("Snapshot") mustBe AssetType.Snapshot
    AssetType.withName("AlbumArt") mustBe AssetType.AlbumArt
  }

  it should "throw NoSuchElementException for invalid name" in {
    assertThrows[NoSuchElementException] {
      AssetType.withName("Invalid")
    }
  }

  it should "support withNameOption for safe lookup" in {
    AssetType.withNameOption("Video") mustBe Some(AssetType.Video)
    AssetType.withNameOption("Invalid") mustBe None
  }

  it should "have correct entryName for each value" in {
    AssetType.Video.entryName mustBe "Video"
    AssetType.Thumbnail.entryName mustBe "Thumbnail"
    AssetType.Snapshot.entryName mustBe "Snapshot"
    AssetType.AlbumArt.entryName mustBe "AlbumArt"
  }
}
