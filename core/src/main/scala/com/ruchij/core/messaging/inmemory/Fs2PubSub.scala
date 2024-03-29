package com.ruchij.core.messaging.inmemory

import cats.{Applicative, Foldable, Functor, Id}
import cats.effect.Concurrent
import cats.implicits._
import com.ruchij.core.messaging.PubSub
import com.ruchij.core.messaging.models.CommittableRecord
import fs2.concurrent.Topic
import fs2.{Pipe, Stream}

class Fs2PubSub[F[_]: Applicative, A] private (topic: Topic[F, A]) extends PubSub[F, CommittableRecord[Id, *], A] {

  override val publish: Pipe[F, A, Unit] = input => topic.publish(input)

  override def publishOne(input: A): F[Unit] = topic.publish1(input).as((): Unit)

  override def subscribe(groupId: String): Stream[F, CommittableRecord[Id, A]] =
    topic.subscribe(Int.MaxValue).map { value => CommittableRecord[Id, A](value, value) }

  override def commit[H[_] : Foldable : Functor](values: H[CommittableRecord[Id, A]]): F[Unit] =
    Applicative[F].unit
}

object Fs2PubSub {
  def apply[F[_]: Concurrent, A]: F[Fs2PubSub[F, A]] =
    Topic[F, A].map(topic => new Fs2PubSub[F, A](topic))
}