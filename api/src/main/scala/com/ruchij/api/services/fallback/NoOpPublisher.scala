package com.ruchij.api.services.fallback

import cats.Applicative
import com.ruchij.core.messaging.Publisher
import fs2.Pipe

final class NoOpPublisher[F[_]: Applicative, A] extends Publisher[F, A] {
  override val publish: Pipe[F, A, Unit] = _.as(())

  override def publishOne(input: A): F[Unit] = Applicative[F].unit
}
