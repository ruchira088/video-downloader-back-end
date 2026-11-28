package com.ruchij.core.daos.workers.models

import com.ruchij.core.types.{EnumEntry, EnumValues}

enum WorkerStatus extends EnumEntry {
  case Active, Reserved, Available, Paused, Deleted
}

object WorkerStatus extends EnumValues[WorkerStatus]
