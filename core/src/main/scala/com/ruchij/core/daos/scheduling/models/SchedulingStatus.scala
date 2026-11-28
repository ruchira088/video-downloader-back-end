package com.ruchij.core.daos.scheduling.models

import com.ruchij.core.types.{EnumEntry, EnumValues}

enum SchedulingStatus extends EnumEntry {
  case Active, Completed, Downloaded, Acquired, Stale, Error, WorkersPaused, Paused, Queued, Deleted
}

object SchedulingStatus extends EnumValues[SchedulingStatus]
