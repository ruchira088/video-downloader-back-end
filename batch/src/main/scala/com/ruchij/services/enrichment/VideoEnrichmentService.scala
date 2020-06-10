package com.ruchij.services.enrichment

import com.ruchij.daos.snapshot.models.Snapshot
import com.ruchij.daos.video.models.Video

import scala.concurrent.duration.FiniteDuration

trait VideoEnrichmentService[F[_]] {
  def videoSnapshots(video: Video): F[Seq[Snapshot]]
}

object VideoEnrichmentService {

  def snapshotTimestamps(video: Video, snapshotCount: Int): Seq[FiniteDuration] = {
    val period = video.videoMetadata.duration / (snapshotCount + 1)

    Range(1, snapshotCount).map(_ * period)
  }

}
