package com.ruchij.core.services.scheduling.models

import org.joda.time.DateTime

case class DownloadProgress(videoId: String, updatedAt: DateTime, bytes: Long)