package com.ruchij.core.daos.scheduling.models

import enumeratum.{Enum, EnumEntry}

sealed trait SchedulingStatus extends EnumEntry {
  val validTransitionStatuses: Set[SchedulingStatus]
}

object SchedulingStatus extends Enum[SchedulingStatus] {
  case object Active extends SchedulingStatus {
    override lazy val validTransitionStatuses: Set[SchedulingStatus] =
      Set(Paused, SchedulerPaused, Completed, Error)
  }

  case object Completed extends SchedulingStatus {
    override lazy val validTransitionStatuses: Set[SchedulingStatus] = Set.empty
  }

  case object Downloaded extends SchedulingStatus {
    override lazy val validTransitionStatuses: Set[SchedulingStatus] = Set(Completed, Error)
  }

  case object Error extends SchedulingStatus {
    override lazy val validTransitionStatuses: Set[SchedulingStatus] = Set(Queued)
  }

  case object Paused extends SchedulingStatus {
    override lazy val validTransitionStatuses: Set[SchedulingStatus] = Set(Queued, SchedulerPaused)
  }

  case object Queued extends SchedulingStatus {
    override lazy val validTransitionStatuses: Set[SchedulingStatus] =
      Set(Active, Error, SchedulerPaused, Paused)
  }

  case object SchedulerPaused extends SchedulingStatus {
    override lazy val validTransitionStatuses: Set[SchedulingStatus] = Set(Queued, Paused)
  }

  override def values: IndexedSeq[SchedulingStatus] = findValues
}