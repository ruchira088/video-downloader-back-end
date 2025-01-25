package com.ruchij.core.daos.scheduling.models

import enumeratum.{Enum, EnumEntry}

sealed trait SchedulingStatus extends EnumEntry { self =>
  val validTransitionStatuses: Set[SchedulingStatus]

  def validateTransition(destination: SchedulingStatus): Either[Throwable, SchedulingStatus] =
    if (validTransitionStatuses.contains(destination)) Right(destination)
    else Left(new IllegalArgumentException(s"Transition not valid: $self -> $destination"))
}

object SchedulingStatus extends Enum[SchedulingStatus] {

  case object Active extends SchedulingStatus {
    override lazy val validTransitionStatuses: Set[SchedulingStatus] =
      Set(Paused, Downloaded, Error, Queued, Stale, WorkersPaused)
  }

  case object Completed extends SchedulingStatus {
    override lazy val validTransitionStatuses: Set[SchedulingStatus] = Set.empty
  }

  case object Downloaded extends SchedulingStatus {
    override lazy val validTransitionStatuses: Set[SchedulingStatus] = Set(Completed, Error)
  }

  case object Acquired extends SchedulingStatus {
    override val validTransitionStatuses: Set[SchedulingStatus] = Set(Active, Downloaded, Error)
  }

  case object Stale extends SchedulingStatus {
    override val validTransitionStatuses: Set[SchedulingStatus] = Set(Active, Error)
  }

  case object Error extends SchedulingStatus {
    override lazy val validTransitionStatuses: Set[SchedulingStatus] = Set(Queued)
  }

  case object WorkersPaused extends SchedulingStatus {
    override val validTransitionStatuses: Set[SchedulingStatus] = Set(Queued, Paused)
  }

  case object Paused extends SchedulingStatus {
    override lazy val validTransitionStatuses: Set[SchedulingStatus] = Set(Queued)
  }

  case object Queued extends SchedulingStatus {
    override lazy val validTransitionStatuses: Set[SchedulingStatus] =
      Set(Acquired, Error, Paused)
  }

  case object Deleted extends SchedulingStatus {
    override val validTransitionStatuses: Set[SchedulingStatus] = Set.empty
  }

  override def values: IndexedSeq[SchedulingStatus] = findValues
}