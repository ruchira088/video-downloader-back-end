package com.ruchij.core.messaging.db

import cats.effect.kernel.{Async, Resource}
import cats.implicits._
import cats.{Applicative, Foldable, Functor, Monad, ~>}
import com.ruchij.core.daos.messaging.MessageConsumerGroupDao
import com.ruchij.core.logging.Logger
import com.ruchij.core.messaging.db.DoobieSubscriber.{BatchSize, Claimed, LastPolledResolution, UniqueViolationSqlState}
import com.ruchij.core.messaging.models.ConsumerGroupRecord
import com.ruchij.core.messaging.{MessagingTopic, Subscriber}
import com.ruchij.core.types.{Clock, RandomGenerator}
import fs2.Stream
import io.circe.parser.parse

import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._
import scala.language.postfixOps

/**
  * Polls the message_queue table on behalf of a consumer group, mirroring the Kafka backend's semantics:
  *
  *   - consumers sharing a group id split the messages between them, while each group receives every message
  *   - a group resumes where it left off, and a brand-new group starts from the latest message
  *   - a message claimed but not committed within `redeliveryTimeout` is delivered to the group again, and one
  *     claimed by a subscription that has ended is delivered again at once
  *
  * Each poll locks the group's row, so concurrent consumers of a group never claim the same message. Claims are
  * tracked per message instead of with a cursor, since ids become visible in commit order rather than in id order.
  * Each poll also moves the group's start id past the messages it has acknowledged, so that polling only scans the
  * recent messages, but never past messages younger than `lateCommitWindow`, since a message with a lower id may
  * still commit until then.
  */
class DoobieSubscriber[F[_]: Async: Clock, G[_]: Monad, A](
  consumerGroupDao: MessageConsumerGroupDao[G],
  pollInterval: FiniteDuration,
  redeliveryTimeout: FiniteDuration,
  maxRetryDelay: FiniteDuration,
  lateCommitWindow: FiniteDuration
)(implicit messagingTopic: MessagingTopic[A], transaction: G ~> F)
    extends Subscriber[F, A] {
  private val logger = Logger[DoobieSubscriber[F, G, A]]

  override type C[X] = ConsumerGroupRecord[Long, X]

  override def subscribe(groupId: String): Stream[F, ConsumerGroupRecord[Long, A]] = {
    val channel = messagingTopic.name

    def poll(consumerId: String, consecutiveFailures: Int): Stream[F, ConsumerGroupRecord[Long, A]] =
      Stream.eval(claim(channel, groupId, consumerId).attempt).flatMap {
        case Right(Claimed(records, size)) =>
          val next =
            if (size < BatchSize) Stream.sleep_[F](pollInterval) ++ poll(consumerId, 0) else poll(consumerId, 0)

          Stream.emits(records) ++ next

        case Left(throwable) =>
          val retryDelay = (pollInterval * math.pow(2, math.min(consecutiveFailures, 16)).toLong).min(maxRetryDelay)

          Stream.exec {
            logger.error[F](
              s"Unable to poll messages for topic=$channel, groupId=$groupId. Retrying in $retryDelay",
              throwable
            )
          } ++ Stream.sleep_[F](retryDelay) ++ poll(consumerId, consecutiveFailures + 1)
      }

    Stream.resource(consumer(groupId)).flatMap(consumerId => poll(consumerId, 0))
  }

  /** Identifies the subscription's claims, and releases the ones it never acknowledged once it ends */
  private def consumer(groupId: String): Resource[F, String] =
    Resource.make(RandomGenerator[F, UUID].generate.map(_.toString)) { consumerId =>
      transaction(consumerGroupDao.releaseClaims(groupId, consumerId)).void.handleErrorWith { throwable =>
        logger.error[F](
          s"Unable to release the claims of consumer=$consumerId in topic=${messagingTopic.name}, groupId=$groupId",
          throwable
        )
      }
    }

  private def claim(channel: String, groupId: String, consumerId: String): F[Claimed[A]] =
    Clock[F].timestamp.flatMap { timestamp =>
      transaction(claimMessages(channel, groupId, consumerId, timestamp)).flatMap {
        case Some((claimed, undecodable)) =>
          undecodable
            .traverse_ {
              case (id, error) =>
                logger.error[F](s"Unable to parse message id=$id for topic=${messagingTopic.name}", error)
            }
            .as(claimed)

        case None =>
          transaction(consumerGroupDao.createGroup(channel, groupId, timestamp))
            .void
            .recover {
              // Another consumer created the group at the same time
              case sqlException: SQLException if sqlException.getSQLState == UniqueViolationSqlState => ()
            }
            .as(Claimed(List.empty, 0))
      }
    }

  private def claimMessages(
    channel: String,
    groupId: String,
    consumerId: String,
    timestamp: Instant
  ): G[Option[(Claimed[A], List[(Long, io.circe.Error)])]] =
    consumerGroupDao.lockGroup(channel, groupId).flatMap {
      _.traverse { lockedStartId =>
        for {
          _ <- consumerGroupDao.markPolled(
            channel,
            groupId,
            timestamp,
            timestamp.minusMillis(LastPolledResolution.toMillis)
          )
          advancedStartId <- consumerGroupDao.advanceStartId(
            channel,
            groupId,
            lockedStartId,
            timestamp.minusMillis(lateCommitWindow.toMillis)
          )

          expired <- consumerGroupDao.findExpiredClaims(
            channel,
            groupId,
            timestamp.minusMillis(redeliveryTimeout.toMillis),
            BatchSize
          )
          _ <- consumerGroupDao.renewClaims(groupId, consumerId, expired.map { case (id, _) => id }, timestamp)

          unclaimed <-
            if (expired.size < BatchSize)
              consumerGroupDao.findUnclaimed(
                channel,
                groupId,
                advancedStartId.getOrElse(lockedStartId),
                BatchSize - expired.size
              )
            else Monad[G].pure(List.empty[(Long, String)])
          _ <- consumerGroupDao.insertClaims(groupId, consumerId, unclaimed.map { case (id, _) => id }, timestamp)

          rows = (expired ++ unclaimed).sortBy { case (id, _) => id }
          decoded = rows.map { case (id, payload) => id -> parse(payload).flatMap(messagingTopic.jsonCodec.decodeJson) }
          records = decoded.collect { case (id, Right(value)) => ConsumerGroupRecord(groupId, id, value) }
          undecodable = decoded.collect { case (id, Left(error)) => id -> error }

          // Acknowledged along with the claim, otherwise they would be redelivered forever
          _ <-
            if (undecodable.isEmpty) Applicative[G].unit
            else consumerGroupDao.acknowledge(groupId, undecodable.map { case (id, _) => id }).void
        } yield (Claimed(records, rows.size), undecodable)
      }
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

  /**
    * How long after a message is created a message with a lower id may still commit. Publishing is a single insert,
    * so a minute is plenty.
    */
  val DefaultLateCommitWindow: FiniteDuration = 1 minute

  /** How often a polling group records that it is alive, which keeps it from expiring */
  private val LastPolledResolution: FiniteDuration = 1 minute

  private val BatchSize: Int = 100

  private val UniqueViolationSqlState = "23505"

  /** The records decoded from a poll, and how many messages the poll claimed, including the undecodable ones */
  private final case class Claimed[A](records: List[ConsumerGroupRecord[Long, A]], size: Int)

  def create[F[_]: Async: Clock, G[_]: Monad, A: MessagingTopic](
    consumerGroupDao: MessageConsumerGroupDao[G],
    pollInterval: FiniteDuration = DefaultPollInterval,
    redeliveryTimeout: FiniteDuration = DefaultRedeliveryTimeout,
    maxRetryDelay: FiniteDuration = DefaultMaxRetryDelay,
    lateCommitWindow: FiniteDuration = DefaultLateCommitWindow
  )(implicit transaction: G ~> F): DoobieSubscriber[F, G, A] =
    new DoobieSubscriber[F, G, A](consumerGroupDao, pollInterval, redeliveryTimeout, maxRetryDelay, lateCommitWindow)
}
