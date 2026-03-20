package com.ruchij.core.messaging.postgres

import cats.implicits._
import cats.{MonadThrow, ~>}
import com.ruchij.core.daos.messaging.MessageDao
import com.ruchij.core.messaging.Publisher
import fs2.Pipe

class PostgresPublisher[F[_]: MonadThrow, G[_]: MonadThrow, A](messageDao: MessageDao[G])(
    implicit postgresTopic: PostgresTopic[A],
    transaction: G ~> F
) extends Publisher[F, A] {

  override val publish: Pipe[F, A, Unit] =
    _.evalMap(publishOne)

  override def publishOne(input: A): F[Unit] = {
    val channel = postgresTopic.channelName
    val payload = postgresTopic.codec(input).noSpaces

    transaction(messageDao.insert(channel, payload) *> messageDao.notify(channel)).void
  }
}
