package com.ruchij.core.daos.scheduling.models

import enumeratum.{Enum, EnumEntry}

sealed trait SchedulingStatus extends EnumEntry

object SchedulingStatus extends Enum[SchedulingStatus] {
  case object Active extends SchedulingStatus
  case object Completed extends SchedulingStatus
  case object Downloaded extends SchedulingStatus
  case object Acquired extends SchedulingStatus
  case object Stale extends SchedulingStatus
  case object Error extends SchedulingStatus
  case object WorkersPaused extends SchedulingStatus
  case object Paused extends SchedulingStatus
  case object Queued extends SchedulingStatus
  case object Deleted extends SchedulingStatus

  override def values: IndexedSeq[SchedulingStatus] = findValues
}