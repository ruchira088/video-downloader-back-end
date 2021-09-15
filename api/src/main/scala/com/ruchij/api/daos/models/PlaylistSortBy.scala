package com.ruchij.api.daos.models

import enumeratum.{Enum, EnumEntry}

sealed trait PlaylistSortBy extends EnumEntry

object PlaylistSortBy extends Enum[PlaylistSortBy] {
  case object Title extends PlaylistSortBy
  case object CreatedAt extends PlaylistSortBy

  override def values: IndexedSeq[PlaylistSortBy] = findValues
}
