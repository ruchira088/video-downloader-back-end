package com.ruchij.api.services.background

trait BackgroundService[F[_]] {
  type Result

  val run: F[Result]
}
