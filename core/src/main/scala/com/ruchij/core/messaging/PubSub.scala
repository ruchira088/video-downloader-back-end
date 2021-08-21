package com.ruchij.core.messaging

import cats.{Foldable, Functor}
import fs2.{Pipe, Stream}

trait PubSub[F[_], G[_], A] extends Publisher[F, A] with Subscriber[F, G, A]

object PubSub {
  def from[F[_], G[_], A](publisher: Publisher[F, A], subscriber: Subscriber[F, G, A]): PubSub[F, G, A] =
    new PubSub[F, G, A] {
      override val publish: Pipe[F, A, Unit] = publisher.publish

      override def publishOne(input: A): F[Unit] = publisher.publishOne(input)

      override def subscribe(groupId: String): Stream[F, G[A]] = subscriber.subscribe(groupId)

      override def commit[H[_]: Foldable: Functor](values: H[G[A]]): F[Unit] = subscriber.commit(values)
    }
}
