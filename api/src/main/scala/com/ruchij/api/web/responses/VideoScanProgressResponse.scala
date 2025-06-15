package com.ruchij.api.web.responses

import com.ruchij.core.daos.workers.models.VideoScan.ScanStatus
import org.joda.time.DateTime

final case class VideoScanProgressResponse(updatedAt: Option[DateTime], scanStatus: ScanStatus)
