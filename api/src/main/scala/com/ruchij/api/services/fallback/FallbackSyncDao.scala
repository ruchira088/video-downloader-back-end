package com.ruchij.api.services.fallback

import com.ruchij.core.daos.scheduling.models.ScheduledVideoDownload

final case class SyncedVideo(scheduledVideoDownload: ScheduledVideoDownload, userIds: List[String])
