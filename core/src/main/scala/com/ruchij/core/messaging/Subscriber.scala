package com.ruchij.core.messaging

import cats.{Foldable, Functor}
import fs2.Stream

trait Subscriber[F[_], G[_], A] {
  def subscribe(groupId: String): Stream[F, G[A]]

  def commit[H[_]: Foldable: Functor](values: H[G[A]]): F[Unit]
}
