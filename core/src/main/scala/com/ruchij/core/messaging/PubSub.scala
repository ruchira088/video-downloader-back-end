package com.ruchij.core.messaging

import cats.Parallel
import cats.effect.kernel.Resource
import cats.effect.{Async, MonadCancelThrow}
import cats.{Foldable, Functor, ~>}
import com.ruchij.core.config.PubsubConfiguration
import com.ruchij.core.daos.doobie.DoobieTransactor
import com.ruchij.core.daos.messaging.DoobieMessageDao
import com.ruchij.core.exceptions.ExternalServiceException
import com.ruchij.core.messaging.db.DoobiePubSub
import com.ruchij.core.messaging.kafka.KafkaPubSub
import com.ruchij.core.messaging.redis.{RedisStreamPublisher, RedisStreamSubscriber}
import com.ruchij.core.types.Clock
import doobie.ConnectionIO
import enumeratum.{Enum, EnumEntry}
import fs2.{Pipe, Stream}

trait PubSub[F[_], A] extends Publisher[F, A] with Subscriber[F, A]

trait PubSubProvider[F[_]] {
  def pubSub[A: MessagingTopic]: Resource[F, PubSub[F, A]]

  /** The transactor backing the message queue when the Doobie backend is configured. */
  val messageTransaction: Option[ConnectionIO ~> F]
}

object PubSub {
  sealed trait PubsubType extends EnumEntry

  object PubsubType extends Enum[PubsubType] {
    case object Kafka extends PubsubType
    case object Redis extends PubsubType
    case object Doobie extends PubsubType

    override def values: IndexedSeq[PubsubType] = findValues
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

  /**
    * Creates the backend-specific resources once (for example a single connection pool for the Doobie backend) and
    * hands out per-topic `PubSub` instances from them.
    */
  def provider[F[_]: Async: Parallel: Clock](pubsubConfiguration: PubsubConfiguration): Resource[F, PubSubProvider[F]] =
    pubsubConfiguration.pubsubType match {
      case PubsubType.Kafka =>
        pubsubConfiguration.kafkaConfiguration
          .fold(missingConfiguration[F]("kafka-configuration", "kafka")) { kafkaConfiguration =>
            Resource.pure {
              new PubSubProvider[F] {
                override def pubSub[A: MessagingTopic]: Resource[F, PubSub[F, A]] = KafkaPubSub[F, A](kafkaConfiguration)

                override val messageTransaction: Option[ConnectionIO ~> F] = None
              }
            }
          }

      case PubsubType.Redis =>
        pubsubConfiguration.redisConfiguration
          .fold(missingConfiguration[F]("redis-configuration", "redis")) { redisConfiguration =>
            Resource.pure {
              new PubSubProvider[F] {
                override def pubSub[A: MessagingTopic]: Resource[F, PubSub[F, A]] =
                  for {
                    publisher <- RedisStreamPublisher.create[F, A](redisConfiguration)
                    subscriber <- RedisStreamSubscriber.create[F, A](redisConfiguration)
                  } yield PubSub.from(publisher, subscriber)

                override val messageTransaction: Option[ConnectionIO ~> F] = None
              }
            }
          }

      case PubsubType.Doobie =>
        pubsubConfiguration.databaseConfiguration
          .fold(missingConfiguration[F]("database-configuration", "doobie")) { databaseConfiguration =>
            DoobieTransactor
              .create[F](databaseConfiguration)
              .map { hikariTransactor =>
                implicit val transaction: ConnectionIO ~> F = hikariTransactor.trans

                new PubSubProvider[F] {
                  override def pubSub[A: MessagingTopic]: Resource[F, PubSub[F, A]] =
                    Resource.pure(DoobiePubSub[F, ConnectionIO, A](DoobieMessageDao))

                  override val messageTransaction: Option[ConnectionIO ~> F] = Some(transaction)
                }
              }
          }
    }

  private def missingConfiguration[F[_]: MonadCancelThrow](key: String, pubsubType: String): Resource[F, PubSubProvider[F]] =
    Resource.eval {
      MonadCancelThrow[F].raiseError {
        ExternalServiceException(s"$key is empty despite the pubsub-type being '$pubsubType'")
      }
    }
}
