package com.ruchij.core.messaging

import cats.Id
import cats.effect.Concurrent
import cats.implicits._
import fs2.concurrent.Topic
import fs2.{Pipe, Stream}

class Fs2PubSub[F[_], A] private (topic: Topic[F, Option[A]]) extends PubSub[F, Id, A] {

  override val publish: Pipe[F, A, Unit] = input => topic.publish(input.map(Some.apply))

  override def publish(input: A): F[Unit] = topic.publish1(Some(input))

  override val subscribe: Stream[F, A] =
    topic.subscribe(Int.MaxValue).collect { case Some(value) => value }
}

object Fs2PubSub {
  def apply[F[_]: Concurrent, A]: F[Fs2PubSub[F, A]] =
    Topic[F, Option[A]](None).map(topic => new Fs2PubSub[F, A](topic))
}