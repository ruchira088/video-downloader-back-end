package com.ruchij.batch.daos.hash.models

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

case class VideoPerceptualHash(
  videoId: String,
  createdAt: Instant,
  duration: FiniteDuration,
  snapshotPerceptualHash: BigInt,
  snapshotTimestamp: FiniteDuration
)
