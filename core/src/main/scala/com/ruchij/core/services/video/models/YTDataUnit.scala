package com.ruchij.core.services.video.models

import enumeratum.{Enum, EnumEntry}

sealed trait YTDataUnit extends EnumEntry {
  def toBytes(value: Double): Double
}

object YTDataUnit extends Enum[YTDataUnit] {
  def unapply(input: String): Option[YTDataUnit] =
    YTDataUnit.withNameInsensitiveOption(input)

  private val Multiplier: Int = 1024

  case object KiB extends YTDataUnit {
    override def toBytes(value: Double): Double = value * Multiplier
  }

  case object MiB extends YTDataUnit {
    override def toBytes(value: Double): Double = KiB.toBytes(value * Multiplier)
  }

  case object GiB extends YTDataUnit {
    override def toBytes(value: Double): Double = MiB.toBytes(value * Multiplier)
  }

  override def values: IndexedSeq[YTDataUnit] = findValues
}
