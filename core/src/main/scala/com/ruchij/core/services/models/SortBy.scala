package com.ruchij.core.services.models

import com.ruchij.core.types.{EnumEntry, EnumValues}

enum SortBy(override val entryName: String) extends EnumEntry {
  case Size extends SortBy("size")
  case Duration extends SortBy("duration")
  case Date extends SortBy("date")
  case Title extends SortBy("title")
  case WatchTime extends SortBy("watch-time")
  case Random extends SortBy("random")
}

object SortBy extends EnumValues[SortBy]
