package com.ruchij.core.services.video.models

import com.ruchij.core.utils.MatcherUtils.DoubleNumber

import scala.util.matching.Regex

final case class YTDataSize(value: Double, unit: YTDataUnit) {
  val bytes: Double = unit.toBytes(value)
}

object YTDataSize {
  private val YTDataSizePattern: Regex = "(\\S+)([GMK]iB)".r

  implicit val ytDataSizeOrdering: Ordering[YTDataSize] =
    (x: YTDataSize, y: YTDataSize) => Ordering[Double].compare(x.bytes, y.bytes)

  def unapply(input: String): Option[YTDataSize] =
    input match {
      case YTDataSizePattern(DoubleNumber(value), YTDataUnit(unit)) => Some(YTDataSize(value, unit))
      case _ => None
    }
}
