package com.ruchij.api.services.fallback

import com.ruchij.api.services.fallback.models.ScheduledVideoUpsert

import java.time.Instant

object ScheduledVideoUpserts {
  def from(syncedVideo: SyncedVideo, capturedAt: Instant): ScheduledVideoUpsert = {
    val video = syncedVideo.scheduledVideoDownload
    val metadata = video.videoMetadata

    val upsert =
      ScheduledVideoUpsert(
        videoId = metadata.id,
        capturedAt = capturedAt,
        hash = "",
        userIds = syncedVideo.userIds.distinct.sorted,
        url = metadata.url.renderString,
        videoSite = metadata.videoSite.name,
        title = metadata.title,
        durationMs = metadata.duration.toMillis,
        sizeBytes = metadata.size,
        status = video.status.entryName,
        scheduledAt = video.scheduledAt,
        completedAt = video.completedAt
      )

    upsert.copy(hash = SyncHash.of(upsert))
  }
}
