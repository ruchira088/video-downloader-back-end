package com.ruchij.core.messaging.postgres

import cats.effect.kernel.Sync
import cats.implicits._
import com.ruchij.core.messaging.Publisher
import doobie._
import doobie.implicits._
import fs2.Pipe

class PostgresPublisher[F[_]: Sync, A](transactor: Transactor[F])(
  implicit postgresTopic: PostgresTopic[A]
) extends Publisher[F, A] {

  override val publish: Pipe[F, A, Unit] =
    _.evalMap(publishOne)

  override def publishOne(input: A): F[Unit] = {
    val channel = postgresTopic.channelName
    val payload = postgresTopic.codec(input).noSpaces

    (sql"INSERT INTO message_queue (channel, payload) VALUES ($channel, $payload)".update.run *>
      Fragment.const(s"NOTIFY $channel").update.run)
      .transact(transactor)
      .void
  }
}

object PostgresPublisher {
  def create[F[_]: Sync, A: PostgresTopic](
    transactor: Transactor[F]
  ): PostgresPublisher[F, A] =
    new PostgresPublisher[F, A](transactor)
}
