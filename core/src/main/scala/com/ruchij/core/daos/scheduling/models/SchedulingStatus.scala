package com.ruchij.core.daos.scheduling.models

import enumeratum.{Enum, EnumEntry}

sealed trait SchedulingStatus extends EnumEntry {
  val validTransitionStatuses: Set[SchedulingStatus]
}

object SchedulingStatus extends Enum[SchedulingStatus] {
  case object Active extends SchedulingStatus {
    override lazy val validTransitionStatuses: Set[SchedulingStatus] =
      Set(Paused, SchedulerPaused, Downloaded, Error, Queued, Stale)
  }

  case object Completed extends SchedulingStatus {
    override lazy val validTransitionStatuses: Set[SchedulingStatus] = Set.empty
  }

  case object Downloaded extends SchedulingStatus {
    override lazy val validTransitionStatuses: Set[SchedulingStatus] = Set(Completed, Error)
  }

  case object Acquired extends SchedulingStatus {
    override val validTransitionStatuses: Set[SchedulingStatus] = Set(Active, Error)
  }

  case object Stale extends SchedulingStatus {
    override val validTransitionStatuses: Set[SchedulingStatus] = Set(Active, Error)
  }

  case object Error extends SchedulingStatus {
    override lazy val validTransitionStatuses: Set[SchedulingStatus] = Set(Queued)
  }

  case object Paused extends SchedulingStatus {
    override lazy val validTransitionStatuses: Set[SchedulingStatus] = Set(Queued, SchedulerPaused)
  }

  case object Queued extends SchedulingStatus {
    override lazy val validTransitionStatuses: Set[SchedulingStatus] =
      Set(Acquired, Error, SchedulerPaused, Paused)
  }

  case object SchedulerPaused extends SchedulingStatus {
    override lazy val validTransitionStatuses: Set[SchedulingStatus] = Set(Queued, Paused)
  }

  override def values: IndexedSeq[SchedulingStatus] = findValues
}