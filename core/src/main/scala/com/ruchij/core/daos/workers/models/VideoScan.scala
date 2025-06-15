package com.ruchij.core.daos.workers.models

import com.ruchij.core.daos.workers.models.VideoScan.ScanStatus
import enumeratum.{Enum, EnumEntry}
import org.joda.time.DateTime

final case class VideoScan(updatedAt: DateTime, status: ScanStatus)

object VideoScan {
  sealed trait ScanStatus extends EnumEntry

  object ScanStatus extends Enum[ScanStatus] {
    case object Idle extends ScanStatus
    case object Scheduled extends ScanStatus
    case object InProgress extends ScanStatus
    case object Error extends ScanStatus

    override def values: IndexedSeq[ScanStatus] = findValues
  }
}
