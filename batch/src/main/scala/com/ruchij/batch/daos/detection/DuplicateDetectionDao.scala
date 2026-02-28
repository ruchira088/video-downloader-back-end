package com.ruchij.batch.daos.detection

import com.ruchij.batch.daos.detection.models.VideoPerceptualHash

import scala.concurrent.duration.FiniteDuration

trait DuplicateDetectionDao[F[_]] {
  val uniqueVideoDurations: F[Set[FiniteDuration]]

  def insert(videoPerceptualHash: VideoPerceptualHash): F[Int]

  def getVideoIdsByDuration(duration: FiniteDuration): F[Seq[String]]

  def findVideoHashesByDuration(duration: FiniteDuration): F[Seq[VideoPerceptualHash]]
}
