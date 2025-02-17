package com.ruchij.core.daos.permission.models

import org.joda.time.DateTime

final case class VideoPermission(grantedAt: DateTime, scheduledVideoDownloadId: String, userId: String)
