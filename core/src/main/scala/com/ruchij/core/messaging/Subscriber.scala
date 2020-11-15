package com.ruchij.core.messaging

import fs2.Stream

trait Subscriber[F[_], G[_], A] {
  val subscribe: Stream[F, G[A]]
}
