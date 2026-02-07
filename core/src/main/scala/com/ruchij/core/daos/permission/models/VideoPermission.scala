package com.ruchij.core.daos.permission.models

import java.time.Instant

final case class VideoPermission(grantedAt: Instant, scheduledVideoDownloadId: String, userId: String)
