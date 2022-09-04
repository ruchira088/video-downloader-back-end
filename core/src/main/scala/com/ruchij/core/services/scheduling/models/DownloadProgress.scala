package com.ruchij.core.services.scheduling.models

import org.joda.time.DateTime

final case class DownloadProgress(videoId: String, updatedAt: DateTime, bytes: Long)