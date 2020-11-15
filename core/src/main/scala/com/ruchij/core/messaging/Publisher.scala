package com.ruchij.core.messaging

import fs2.Pipe

trait Publisher[F[_], A] {
  val publish: Pipe[F, A, Unit]

  def publish(input: A): F[Unit]
}
