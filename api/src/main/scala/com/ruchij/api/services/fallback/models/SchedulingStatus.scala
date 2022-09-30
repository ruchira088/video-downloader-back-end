package com.ruchij.api.services.fallback.models

import enumeratum.{Enum, EnumEntry}

sealed trait SchedulingStatus extends EnumEntry

object SchedulingStatus extends Enum[SchedulingStatus] {
  case object Pending extends SchedulingStatus
  case object Locked extends SchedulingStatus
  case object Acknowledged extends SchedulingStatus

  override def values: IndexedSeq[SchedulingStatus] = findValues
}