package com.ruchij.core.messaging

import cats.{Foldable, Functor}
import fs2.Stream

trait Subscriber[F[_], A] {
  type C[_]

  def subscribe(groupId: String): Stream[F, C[A]]

  def commit[H[_]: Foldable: Functor](values: H[C[A]]): F[Unit]
}
