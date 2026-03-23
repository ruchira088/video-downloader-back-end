package com.ruchij.core.messaging

import cats.effect.kernel.Resource
import cats.effect.{Async, MonadCancelThrow}
import cats.{Foldable, Functor}
import com.ruchij.core.config.PubSubConfiguration
import com.ruchij.core.daos.doobie.DoobieTransactor
import com.ruchij.core.daos.messaging.MessageDao
import com.ruchij.core.exceptions.ExternalServiceException
import com.ruchij.core.messaging.db.DoobiePubSub
import com.ruchij.core.messaging.kafka.KafkaPubSub
import com.ruchij.core.messaging.redis.{RedisStreamPublisher, RedisStreamSubscriber}
import com.ruchij.core.types.Clock
import doobie.ConnectionIO
import enumeratum.{Enum, EnumEntry}
import fs2.{Pipe, Stream}

trait PubSub[F[_], A] extends Publisher[F, A] with Subscriber[F, A]

object PubSub {
  sealed trait PubSubType extends EnumEntry

  object PubSubType extends Enum[PubSubType] {
    case object Kafka extends PubSubType
    case object Redis extends PubSubType
    case object Doobie extends PubSubType

    override def values: IndexedSeq[PubSubType] = findValues
  }

  def from[F[_], A](publisher: Publisher[F, A], subscriber: Subscriber[F, A]): PubSub[F, A] =
    new PubSub[F, A] {
      override type C[X] = subscriber.C[X]

      override val publish: Pipe[F, A, Unit] = publisher.publish

      override def publishOne(input: A): F[Unit] = publisher.publishOne(input)

      override def subscribe(groupId: String): Stream[F, subscriber.C[A]] = subscriber.subscribe(groupId)

      override def commit[H[_]: Foldable: Functor](values: H[subscriber.C[A]]): F[Unit] = subscriber.commit(values)

      override def extractValue(ca: subscriber.C[A]): A = subscriber.extractValue(ca)
    }

  def apply[F[_]: Async: Clock, A: MessagingTopic](
    pubSubConfiguration: PubSubConfiguration,
    messageDao: MessageDao[ConnectionIO]
  ): Resource[F, PubSub[F, A]] =
    pubSubConfiguration.pubSubType match {
      case PubSubType.Kafka =>
        pubSubConfiguration.kafkaConfiguration
          .fold[Resource[F, PubSub[F, A]]](
            Resource.eval(
              MonadCancelThrow[F].raiseError(
                ExternalServiceException("kafka-configuration is empty despite the pubsub-type being 'kafka'")
              )
            )
          ) { kafkaConfiguration =>
            KafkaPubSub(kafkaConfiguration)
          }

      case PubSubType.Redis =>
        pubSubConfiguration.redisConfiguration
          .fold[Resource[F, PubSub[F, A]]](
            Resource.eval(
              MonadCancelThrow[F].raiseError(
                ExternalServiceException("redis-configuration is empty despite the pubsub-type being 'redis'")
              )
            )
          ) { redisConfiguration =>
            for {
              publisher <- RedisStreamPublisher.create[F, A](redisConfiguration)
              subscriber <- RedisStreamSubscriber.create[F, A](redisConfiguration)
            } yield PubSub.from(publisher, subscriber)
          }

      case PubSubType.Doobie =>
        pubSubConfiguration.databaseConfiguration
          .fold[Resource[F, PubSub[F, A]]](
            Resource.eval(
              MonadCancelThrow[F].raiseError(
                ExternalServiceException("database-configuration is empty despite the pubsub-type being 'doobie'")
              )
            )
          ) { databaseConfiguration =>
            DoobieTransactor
              .create[F](databaseConfiguration)
              .map { hikariTransactor =>
                hikariTransactor.trans
              }
              .map { implicit transaction =>
                DoobiePubSub[F, ConnectionIO, A](messageDao)
              }
          }

    }
}
