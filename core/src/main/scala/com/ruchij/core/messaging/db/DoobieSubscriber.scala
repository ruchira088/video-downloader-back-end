package com.ruchij.core.messaging.db

import cats.effect.kernel.Async
import cats.implicits._
import cats.{Applicative, Foldable, Functor, Monad, ~>}
import com.ruchij.core.daos.messaging.MessageConsumerGroupDao
import com.ruchij.core.logging.Logger
import com.ruchij.core.messaging.db.DoobieSubscriber.{BatchSize, UniqueViolationSqlState}
import com.ruchij.core.messaging.models.ConsumerGroupRecord
import com.ruchij.core.messaging.{MessagingTopic, Subscriber}
import com.ruchij.core.types.Clock
import fs2.Stream
import io.circe.parser.parse

import java.sql.SQLException
import java.time.Instant
import scala.concurrent.duration._
import scala.language.postfixOps

/**
  * Polls the message_queue table on behalf of a consumer group, mirroring the Kafka backend's semantics:
  *
  *   - consumers sharing a group id split the messages between them, while each group receives every message
  *   - a group resumes where it left off, and a brand-new group starts from the latest message
  *   - a message claimed but not committed within `redeliveryTimeout` is delivered to the group again
  *
  * Each poll locks the group's row, so concurrent consumers of a group never claim the same message. Claims are
  * tracked per message instead of with a cursor, since ids become visible in commit order rather than in id order.
  */
class DoobieSubscriber[F[_]: Async: Clock, G[_]: Monad, A](
  consumerGroupDao: MessageConsumerGroupDao[G],
  pollInterval: FiniteDuration,
  redeliveryTimeout: FiniteDuration,
  maxRetryDelay: FiniteDuration
)(implicit messagingTopic: MessagingTopic[A], transaction: G ~> F)
    extends Subscriber[F, A] {
  private val logger = Logger[DoobieSubscriber[F, G, A]]

  override type C[X] = ConsumerGroupRecord[Long, X]

  override def subscribe(groupId: String): Stream[F, ConsumerGroupRecord[Long, A]] = {
    val channel = messagingTopic.name

    def poll(consecutiveFailures: Int): Stream[F, ConsumerGroupRecord[Long, A]] =
      Stream.eval(claim(channel, groupId).attempt).flatMap {
        case Right(rows) =>
          val next = if (rows.size < BatchSize) Stream.sleep_[F](pollInterval) ++ poll(0) else poll(0)
          Stream.emits(rows).evalMapFilter { case (id, payload) => decode(groupId, id, payload) } ++ next

        case Left(throwable) =>
          val retryDelay = (pollInterval * math.pow(2, math.min(consecutiveFailures, 16)).toLong).min(maxRetryDelay)

          Stream.exec {
            logger.error[F](
              s"Unable to poll messages for topic=$channel, groupId=$groupId. Retrying in $retryDelay",
              throwable
            )
          } ++ Stream.sleep_[F](retryDelay) ++ poll(consecutiveFailures + 1)
      }

    poll(0)
  }

  private def claim(channel: String, groupId: String): F[List[(Long, String)]] =
    Clock[F].timestamp.flatMap { timestamp =>
      transaction(claimMessages(channel, groupId, timestamp)).flatMap {
        case Some(rows) => Applicative[F].pure(rows)

        case None =>
          transaction(consumerGroupDao.createGroup(channel, groupId, timestamp))
            .void
            .recover {
              // Another consumer created the group at the same time
              case sqlException: SQLException if sqlException.getSQLState == UniqueViolationSqlState => ()
            }
            .as(List.empty)
      }
    }

  private def claimMessages(channel: String, groupId: String, timestamp: Instant): G[Option[List[(Long, String)]]] =
    consumerGroupDao.lockGroup(channel, groupId).flatMap {
      _.traverse { startId =>
        for {
          expired <- consumerGroupDao.findExpiredClaims(
            channel,
            groupId,
            timestamp.minusMillis(redeliveryTimeout.toMillis),
            BatchSize
          )
          _ <- consumerGroupDao.renewClaims(groupId, expired.map { case (id, _) => id }, timestamp)

          unclaimed <-
            if (expired.size < BatchSize)
              consumerGroupDao.findUnclaimed(channel, groupId, startId, BatchSize - expired.size)
            else Monad[G].pure(List.empty[(Long, String)])
          _ <- consumerGroupDao.insertClaims(groupId, unclaimed.map { case (id, _) => id }, timestamp)
        } yield (expired ++ unclaimed).sortBy { case (id, _) => id }
      }
    }

  private def decode(groupId: String, id: Long, payload: String): F[Option[ConsumerGroupRecord[Long, A]]] =
    parse(payload).flatMap(messagingTopic.jsonCodec.decodeJson) match {
      case Right(value) => Applicative[F].pure(Some(ConsumerGroupRecord(groupId, id, value)))

      case Left(error) =>
        // Acknowledge the message, otherwise it would be redelivered forever
        logger
          .error[F](s"Unable to parse message id=$id for topic=${messagingTopic.name}", error)
          .productR(transaction(consumerGroupDao.acknowledge(groupId, List(id))))
          .as(None)
    }

  override def commit[H[_]: Foldable: Functor](values: H[ConsumerGroupRecord[Long, A]]): F[Unit] =
    values.toList
      .groupMap(_.groupId)(_.id)
      .toList
      .traverse_ { case (groupId, ids) => transaction(consumerGroupDao.acknowledge(groupId, ids)) }

  override def extractValue(ca: ConsumerGroupRecord[Long, A]): A = ca.value
}

object DoobieSubscriber {
  val DefaultPollInterval: FiniteDuration = 1 second

  /** Mirrors Kafka's default max.poll.interval.ms */
  val DefaultRedeliveryTimeout: FiniteDuration = 5 minutes

  val DefaultMaxRetryDelay: FiniteDuration = 30 seconds

  private val BatchSize: Int = 100

  private val UniqueViolationSqlState = "23505"

  def create[F[_]: Async: Clock, G[_]: Monad, A: MessagingTopic](
    consumerGroupDao: MessageConsumerGroupDao[G],
    pollInterval: FiniteDuration = DefaultPollInterval,
    redeliveryTimeout: FiniteDuration = DefaultRedeliveryTimeout,
    maxRetryDelay: FiniteDuration = DefaultMaxRetryDelay
  )(implicit transaction: G ~> F): DoobieSubscriber[F, G, A] =
    new DoobieSubscriber[F, G, A](consumerGroupDao, pollInterval, redeliveryTimeout, maxRetryDelay)
}
