package com.ruchij.core.messaging

import fs2.Pipe

trait Publisher[F[_], A] {
  val publish: Pipe[F, A, Unit]

  def publishOne(input: A): F[Unit]
}
