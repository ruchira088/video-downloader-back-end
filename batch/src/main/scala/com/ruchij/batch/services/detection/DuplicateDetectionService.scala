package com.ruchij.batch.services.detection

import scala.concurrent.duration.FiniteDuration

trait DuplicateDetectionService[F[_]] {
  def detect: F[Map[FiniteDuration, Set[Set[String]]]]

  def run: F[Unit]
}

object DuplicateDetectionService {
  val DifferenceThreshold: Double = 0.15
}
