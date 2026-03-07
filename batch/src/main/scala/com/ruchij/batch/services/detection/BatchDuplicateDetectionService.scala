package com.ruchij.batch.services.detection

import scala.concurrent.duration.FiniteDuration

trait BatchDuplicateDetectionService[F[_]] {
  def detect: F[Map[FiniteDuration, Set[Set[String]]]]

  def run: F[Unit]
}

object BatchDuplicateDetectionService {
  val DifferenceThreshold: Double = 0.2
}
