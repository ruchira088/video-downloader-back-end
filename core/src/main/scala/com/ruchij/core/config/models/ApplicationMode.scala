package com.ruchij.core.config.models

import enumeratum.{Enum, EnumEntry}

sealed trait ApplicationMode extends EnumEntry

object ApplicationMode extends Enum[ApplicationMode] {
  case object Production extends ApplicationMode
  case object Development extends ApplicationMode
  case object Test extends ApplicationMode

  override def values: IndexedSeq[ApplicationMode] = findValues
}
