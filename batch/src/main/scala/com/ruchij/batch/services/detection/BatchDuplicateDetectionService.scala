package com.ruchij.batch.services.detection

import com.ruchij.batch.services.enrichment.VideoEnrichmentService
import com.ruchij.batch.services.enrichment.VideoEnrichmentService.SnapshotCount
import com.ruchij.core.daos.video.models.Video

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

trait BatchDuplicateDetectionService[F[_]] {
  def detect: F[Map[FiniteDuration, Set[Set[String]]]]

  /**
    * Detects duplicate videos and, in each group of duplicates, deletes every video except the one `videoToKeep` picks.
    * A video is only deleted when the same users hold it and the kept video, and it matches the kept video at every one
    * of the `verificationSnapshotTimestamps`; otherwise it stays in the group.
    */
  def run: F[Unit]
}

object BatchDuplicateDetectionService {
  val DifferenceThreshold: Double = 0.2

  /** The snapshots, a quarter, half and three quarters of the way through the video, compared before deleting it. */
  def verificationSnapshotTimestamps(videoDuration: FiniteDuration): Seq[FiniteDuration] = {
    val snapshotTimestamps = VideoEnrichmentService.snapshotTimestamps(videoDuration, SnapshotCount).sorted

    Seq(snapshotTimestamps.size / 4, snapshotTimestamps.size / 2, 3 * snapshotTimestamps.size / 4)
      .map(snapshotTimestamps)
  }

  /**
    * Picks the duplicate to keep: the one with the largest file, taken to be the highest quality. Of files that are the
    * same size, the newest video is kept. The video ID breaks any remaining tie, so that every batch instance picks the
    * same video.
    */
  def videoToKeep(videos: Seq[Video]): Option[Video] =
    videos.maxByOption(video => (video.fileResource.size, video.createdAt, video.id))(
      using Ordering.Tuple3(using Ordering.Long, Ordering.fromLessThan[Instant](_.isBefore(_)), Ordering.String)
    )
}
