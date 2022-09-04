package com.ruchij.api.daos.permission.models

import org.joda.time.DateTime

final case class VideoPermission(grantedAt: DateTime, scheduledVideoDownloadId: String, userId: String)
