package com.ruchij.core.daos.workers.models

import enumeratum.{Enum, EnumEntry}

sealed trait WorkerStatus extends EnumEntry

object WorkerStatus extends Enum[WorkerStatus] {
  case object Active extends WorkerStatus
  case object Reserved extends WorkerStatus
  case object Available extends WorkerStatus
  case object Paused extends WorkerStatus
  case object Deleted extends WorkerStatus

  override def values: IndexedSeq[WorkerStatus] = findValues
}
