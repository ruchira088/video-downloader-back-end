package com.ruchij.api.services.asset.models

import com.ruchij.core.types.{EnumEntry, EnumValues}

enum AssetType extends EnumEntry {
  case Video, Thumbnail, Snapshot, AlbumArt
}

object AssetType extends EnumValues[AssetType]
