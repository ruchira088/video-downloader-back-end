package com.ruchij.core.messaging.db

import cats.implicits._
import cats.{MonadThrow, ~>}
import com.ruchij.core.daos.messaging.MessageDao
import com.ruchij.core.messaging.{MessagingTopic, Publisher}
import com.ruchij.core.types.Clock
import fs2.Pipe

class DoobiePublisher[F[_]: MonadThrow: Clock, G[_], A](messageDao: MessageDao[G])(
  implicit messagingTopic: MessagingTopic[A],
  transaction: G ~> F
) extends Publisher[F, A] {

  override val publish: Pipe[F, A, Unit] =
    _.evalMap(publishOne)

  override def publishOne(input: A): F[Unit] =
    for {
      now <- Clock[F].timestamp
      channel = messagingTopic.name
      payload = messagingTopic.jsonCodec(input).noSpaces
      _ <- transaction(messageDao.insert(channel, payload, now))
    } yield ()
}
