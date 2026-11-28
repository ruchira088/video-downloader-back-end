package com.ruchij.api.daos.playlist.models

import com.ruchij.core.types.{EnumEntry, EnumValues}
import doobie.implicits.toSqlInterpolator
import doobie.util.fragment.Fragment

enum PlaylistSortBy(val fragment: Fragment) extends EnumEntry {
  case Title extends PlaylistSortBy(fr"title")
  case CreatedAt extends PlaylistSortBy(fr"created_at")
}

object PlaylistSortBy extends EnumValues[PlaylistSortBy]
