package com.ruchij.daos.scheduling.models

import com.ruchij.daos.video.models.VideoMetadata
import org.joda.time.DateTime

case class ScheduledVideoDownload(scheduledAt: DateTime, videoMetadata: VideoMetadata)
