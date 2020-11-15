package com.ruchij.core.messaging

import fs2.Stream

trait Subscriber[F[_], A] {
  val subscribe: Stream[F, A]
}
