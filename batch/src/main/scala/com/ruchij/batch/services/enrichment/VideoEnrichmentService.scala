package com.ruchij.batch.services.enrichment

import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.snapshot.models.Snapshot
import com.ruchij.core.daos.video.models.Video
import org.http4s.MediaType

import scala.concurrent.duration.FiniteDuration

trait VideoEnrichmentService[F[_]] {
  val snapshotMediaType: MediaType

  def videoSnapshots(video: Video): F[List[Snapshot]]

  def snapshotFileResource(videoPath: String, snapshotPath: String, videoTimestamp: FiniteDuration): F[FileResource]
}

object VideoEnrichmentService {

  def snapshotTimestamps(video: Video, snapshotCount: Int): Seq[FiniteDuration] = {
    val period = video.videoMetadata.duration / (snapshotCount + 1)

    Range(1, snapshotCount + 1).map(_ * period)
  }
}
