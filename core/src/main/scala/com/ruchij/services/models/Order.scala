package com.ruchij.services.models

import enumeratum.{Enum, EnumEntry}

sealed trait Order extends EnumEntry

object Order extends Enum[Order] {
  case object Ascending extends Order {
    override def entryName: String = "asc"
  }

  case object Descending extends Order {
    override def entryName: String = "desc"
  }

  override val values: IndexedSeq[Order] = findValues
}
