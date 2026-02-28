package com.ruchij.batch.services.detection

import scala.concurrent.duration.FiniteDuration

trait DuplicateDetectionService[F[_]] {
  val detect:  F[Map[FiniteDuration, Set[Set[String]]]]

  val run: F[Unit]
}

object DuplicateDetectionService {
  val DifferenceThreshold: Double = 0.15
}
