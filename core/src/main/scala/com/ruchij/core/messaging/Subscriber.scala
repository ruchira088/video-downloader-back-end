package com.ruchij.core.messaging

import fs2.Stream

trait Subscriber[F[_], G[_], A] {
  def subscribe(groupId: String): Stream[F, G[A]]
}
