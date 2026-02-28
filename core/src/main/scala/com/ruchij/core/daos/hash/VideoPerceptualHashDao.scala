package com.ruchij.core.daos.hash

import com.ruchij.core.daos.hash.models.VideoPerceptualHash

import scala.concurrent.duration.FiniteDuration

trait VideoPerceptualHashDao[F[_]] {
  val uniqueVideoDurations: F[Set[FiniteDuration]]

  def insert(videoPerceptualHash: VideoPerceptualHash): F[Int]

  def getVideoIdsByDuration(duration: FiniteDuration): F[Seq[String]]

  def findVideoHashesByDuration(duration: FiniteDuration): F[Seq[VideoPerceptualHash]]

  def getByVideoId(videoId: String): F[List[VideoPerceptualHash]]
}
