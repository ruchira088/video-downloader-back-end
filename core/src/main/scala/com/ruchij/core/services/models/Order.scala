package com.ruchij.core.services.models

import com.ruchij.core.types.{EnumEntry, EnumValues}

enum Order(override val entryName: String) extends EnumEntry {
  case Ascending extends Order("asc")
  case Descending extends Order("desc")
}

object Order extends EnumValues[Order]
