package com.ruchij.api.web.responses

import com.ruchij.core.daos.workers.models.VideoScan.ScanStatus
import java.time.Instant

final case class VideoScanProgressResponse(updatedAt: Option[Instant], scanStatus: ScanStatus)
