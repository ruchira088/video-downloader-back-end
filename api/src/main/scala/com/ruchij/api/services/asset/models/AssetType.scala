package com.ruchij.api.services.asset.models

import enumeratum.{Enum, EnumEntry}

sealed trait AssetType extends EnumEntry

object AssetType extends Enum[AssetType] {
  case object Video extends AssetType
  case object Thumbnail extends AssetType
  case object Snapshot extends AssetType
  case object AlbumArt extends AssetType

  override def values: IndexedSeq[AssetType] = findValues
}
