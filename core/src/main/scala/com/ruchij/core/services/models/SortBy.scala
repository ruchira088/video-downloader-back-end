package com.ruchij.core.services.models

import enumeratum.{Enum, EnumEntry}

sealed trait SortBy extends EnumEntry

object SortBy extends Enum[SortBy] {

  case object Size extends SortBy {
    override val entryName: String = "size"
  }

  case object Duration extends SortBy {
    override val entryName: String = "duration"
  }

  case object Date extends SortBy {
    override val entryName: String = "date"
  }

  case object Title extends SortBy {
    override val entryName: String = "title"
  }

  case object WatchTime extends SortBy {
    override def entryName: String = "watch-time"
  }

  case object Random extends SortBy  {
    override def entryName: String = "random"
  }

  override val values: IndexedSeq[SortBy] = findValues
}
