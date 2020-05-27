package com.ruchij.daos.workers.models

import com.ruchij.daos.scheduling.models.ScheduledVideoDownload
import org.joda.time.DateTime

case class Worker(id: String, reservedAt: Option[DateTime], scheduledVideoDownload: Option[ScheduledVideoDownload])
