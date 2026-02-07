package com.ruchij.core.services.scheduling.models

import java.time.Instant

final case class DownloadProgress(videoId: String, updatedAt: Instant, bytes: Long)