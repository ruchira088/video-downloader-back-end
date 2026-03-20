package com.ruchij.core.messaging.doobie

import cats.implicits._
import cats.{MonadThrow, ~>}
import com.ruchij.core.daos.messaging.MessageDao
import com.ruchij.core.messaging.Publisher
import fs2.Pipe

class DoobiePublisher[F[_]: MonadThrow, G[_], A](messageDao: MessageDao[G])(
    implicit doobieTopic: DoobieTopic[A],
    transaction: G ~> F
) extends Publisher[F, A] {

  override val publish: Pipe[F, A, Unit] =
    _.evalMap(publishOne)

  override def publishOne(input: A): F[Unit] = {
    val channel = doobieTopic.topicName
    val payload = doobieTopic.codec(input).noSpaces

    transaction(messageDao.insert(channel, payload)).void
  }
}
