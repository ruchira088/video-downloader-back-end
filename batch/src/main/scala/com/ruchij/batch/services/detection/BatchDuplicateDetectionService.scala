package com.ruchij.batch.services.detection

import com.ruchij.core.daos.video.models.Video

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

trait BatchDuplicateDetectionService[F[_]] {
  def detect: F[Map[FiniteDuration, Set[Set[String]]]]

  /** Detects duplicate videos and, in each group of duplicates, deletes every video except the one `videoToKeep` picks. */
  def run: F[Unit]
}

object BatchDuplicateDetectionService {
  val DifferenceThreshold: Double = 0.2

  /**
    * Picks the duplicate to keep: the one with the largest file, taken to be the highest quality. Of files that are the
    * same size, the newest video is kept. The video ID breaks any remaining tie, so that every batch instance picks the
    * same video.
    */
  def videoToKeep(videos: Seq[Video]): Option[Video] =
    videos.maxByOption(video => (video.fileResource.size, video.createdAt, video.id))(
      Ordering.Tuple3(Ordering.Long, Ordering.fromLessThan[Instant](_.isBefore(_)), Ordering.String)
    )
}
