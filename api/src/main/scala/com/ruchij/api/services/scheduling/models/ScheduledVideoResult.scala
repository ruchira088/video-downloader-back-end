package com.ruchij.api.services.scheduling.models

import com.ruchij.core.daos.scheduling.models.ScheduledVideoDownload

sealed trait ScheduledVideoResult {
  val scheduledVideoDownload: ScheduledVideoDownload
  val isNew: Boolean
}

object ScheduledVideoResult {
  final case class AlreadyScheduled(scheduledVideoDownload: ScheduledVideoDownload) extends ScheduledVideoResult {
    override val isNew: Boolean = false
  }

  final case class NewlyScheduled(scheduledVideoDownload: ScheduledVideoDownload) extends ScheduledVideoResult {
    override val isNew: Boolean = true
  }
}
