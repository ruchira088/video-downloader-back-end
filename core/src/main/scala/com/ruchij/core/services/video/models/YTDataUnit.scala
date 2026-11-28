package com.ruchij.core.services.video.models

import com.ruchij.core.types.{EnumEntry, EnumValues}

enum YTDataUnit extends EnumEntry {
  case KiB, MiB, GiB

  def toBytes(value: Double): Double =
    this match {
      case KiB => value * YTDataUnit.Multiplier
      case MiB => KiB.toBytes(value * YTDataUnit.Multiplier)
      case GiB => MiB.toBytes(value * YTDataUnit.Multiplier)
    }
}

object YTDataUnit extends EnumValues[YTDataUnit] {
  def unapply(input: String): Option[YTDataUnit] =
    YTDataUnit.withNameInsensitiveOption(input)

  private val Multiplier: Int = 1024
}
