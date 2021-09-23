package com.ruchij.api.daos.playlist.models

import doobie.implicits.toSqlInterpolator
import doobie.util.fragment.Fragment
import enumeratum.{Enum, EnumEntry}

sealed trait PlaylistSortBy extends EnumEntry {
  val fragment: Fragment
}

object PlaylistSortBy extends Enum[PlaylistSortBy] {
  case object Title extends PlaylistSortBy {
    override val fragment: Fragment = fr"title"
  }

  case object CreatedAt extends PlaylistSortBy {
    override val fragment: Fragment = fr"created_at"
  }

  override def values: IndexedSeq[PlaylistSortBy] = findValues
}
