package com.ruchij.core.messaging.redis

import cats.effect.kernel.{Async, Resource}
import cats.implicits._
import cats.{Applicative, Foldable, Functor}
import com.ruchij.core.config.RedisConfiguration
import com.ruchij.core.logging.Logger
import com.ruchij.core.messaging.models.ConsumerGroupRecord
import com.ruchij.core.messaging.redis.RedisStreamSubscriber.{DeleteStaleConsumersScript, Settings}
import com.ruchij.core.messaging.{MessagingTopic, Subscriber}
import com.ruchij.core.types.RandomGenerator
import dev.profunktor.redis4cats.{Redis, RedisCommands}
import dev.profunktor.redis4cats.effect.Log.Stdout.instance
import dev.profunktor.redis4cats.effects._
import fs2.Stream

import java.util.UUID
import scala.concurrent.duration._
import scala.language.postfixOps

/**
  * Reads a Redis stream through a consumer group, mirroring the Kafka backend's semantics:
  *
  *   - consumers sharing a group id split the messages between them, while each group receives every message
  *   - a group resumes where it left off, and a brand-new group starts from the latest message
  *   - a message read but not committed (XACK) within `redeliveryTimeout` is claimed again by a live consumer, and
  *     one read by a subscription that has ended can be claimed again at once
  *
  * Each subscription reads on its own connection, since a blocking XREADGROUP holds up every other command on the
  * connection it is sent on. Every `redeliveryTimeout`, each subscription also deletes the group's consumers that
  * hold no pending messages and have been idle for longer than `redeliveryTimeout`, which are left behind by ended
  * subscriptions whose messages were claimed by other consumers.
  */
class RedisStreamSubscriber[F[_]: Async, A](
  redisCommands: RedisCommands[F, String, String],
  readerConnection: Resource[F, RedisCommands[F, String, String]],
  settings: Settings
)(implicit messagingTopic: MessagingTopic[A])
    extends Subscriber[F, A] {
  private val logger = Logger[RedisStreamSubscriber[F, A]]

  private val streamKey: String = messagingTopic.name

  override type C[X] = ConsumerGroupRecord[String, X]

  override def subscribe(groupId: String): Stream[F, ConsumerGroupRecord[String, A]] =
    for {
      consumerName <- Stream.resource(consumer(groupId))
      reader <- Stream.resource(readerConnection)
      record <- read(reader, StreamConsumer(groupId, consumerName), groupExists = false, consecutiveFailures = 0)
        .concurrently(deleteStaleConsumers(groupId))
    } yield record

  private def consumer(groupId: String): Resource[F, String] =
    Resource.make(RandomGenerator[F, UUID].generate.map(uuid => s"$groupId-$uuid")) { consumerName =>
      val streamConsumer = StreamConsumer(groupId, consumerName)

      releasePending(streamConsumer, XRangePoint.Unbounded, released = 0)
        .flatMap { released =>
          // A consumer still holding pending messages is kept, otherwise they could never be claimed again. It is
          // deleted by deleteStaleConsumers once they have been claimed.
          if (released == 0) redisCommands.xGroupDelConsumer(streamKey, streamConsumer).void else Applicative[F].unit
        }
        .handleErrorWith { throwable =>
          logger.error[F](s"Unable to release consumer=$consumerName from groupId=$groupId", throwable)
        }
    }

  /**
    * Makes the consumer's pending messages claimable by the group's other consumers at once, rather than only after
    * `redeliveryTimeout`, by claiming them for itself with their idle time set to `redeliveryTimeout`. Redis has no
    * way of handing pending messages back to the group. Returns how many messages it released.
    */
  private def releasePending(streamConsumer: StreamConsumer[String], start: XRangePoint, released: Int): F[Int] =
    redisCommands
      .xPending(streamKey, streamConsumer, start, XRangePoint.Unbounded, settings.batchSize)
      .flatMap { pending =>
        pending.lastOption.fold(Applicative[F].pure(released)) { last =>
          redisCommands
            .xClaim(
              streamKey,
              streamConsumer,
              XClaimArgs(0 seconds, idle = Some(XClaimIdle.Relative(settings.redeliveryTimeout)), justId = true),
              pending.map(_.id.value): _*
            )
            .productR {
              if (pending.size < settings.batchSize) Applicative[F].pure(released + pending.size)
              else releasePending(streamConsumer, XRangePoint.Exclusive(last.id.value), released + pending.size)
            }
        }
      }

  private def deleteStaleConsumers(groupId: String): Stream[F, Nothing] =
    Stream
      .awakeEvery[F](settings.redeliveryTimeout)
      .evalMap { _ =>
        redisCommands
          .eval(
            DeleteStaleConsumersScript,
            ScriptOutputType.Integer,
            List(streamKey),
            List(groupId, settings.redeliveryTimeout.toMillis.toString)
          )
          .void
          .handleErrorWith { throwable =>
            logger.error[F](s"Unable to delete stale consumers from stream=$streamKey, groupId=$groupId", throwable)
          }
      }
      .drain

  private def read(
    reader: RedisCommands[F, String, String],
    streamConsumer: StreamConsumer[String],
    groupExists: Boolean,
    consecutiveFailures: Int
  ): Stream[F, ConsumerGroupRecord[String, A]] =
    Stream
      .eval {
        (if (groupExists) Applicative[F].unit else createGroup(streamConsumer.group))
          .productR(readMessages(reader, streamConsumer))
          .attempt
      }
      .flatMap {
        case Right(messages) =>
          Stream.emits(messages).evalMapFilter(decode(streamConsumer.group)) ++
            read(reader, streamConsumer, groupExists = true, consecutiveFailures = 0)

        case Left(throwable) =>
          val delay = retryDelay(consecutiveFailures)

          Stream.exec {
            logger.error[F](
              s"Unable to read from stream=$streamKey for groupId=${streamConsumer.group}. Retrying in $delay",
              throwable
            )
          } ++
            Stream.sleep_[F](delay) ++
            // The stream (and with it the group) may have been deleted, so create the group again
            read(reader, streamConsumer, groupExists = false, consecutiveFailures = consecutiveFailures + 1)
      }

  private def retryDelay(consecutiveFailures: Int): FiniteDuration =
    (settings.retryDelay * math.pow(2, math.min(consecutiveFailures, 16)).toLong).min(settings.maxRetryDelay)

  private def retrying[B](description: String, consecutiveFailures: Int = 0)(fb: F[B]): F[B] =
    fb.handleErrorWith { throwable =>
      val delay = retryDelay(consecutiveFailures)

      logger.error[F](s"$description. Retrying in $delay", throwable) *>
        Async[F].sleep(delay) *>
        retrying(description, consecutiveFailures + 1)(fb)
    }

  /** Creates the group from the latest message if it doesn't exist yet, as Kafka does with auto.offset.reset=latest */
  private def createGroup(groupId: String): F[Unit] =
    redisCommands
      .xGroupCreate(streamKey, groupId, "$", XGroupCreateArgs(mkStream = true))
      .recover {
        case throwable if Option(throwable.getMessage).exists(_.contains("BUSYGROUP")) => ()
      }

  private def readMessages(
    reader: RedisCommands[F, String, String],
    streamConsumer: StreamConsumer[String]
  ): F[List[StreamMessage[String, String]]] =
    reader
      .xAutoClaim(
        streamKey,
        XAutoClaimArgs(streamConsumer, settings.redeliveryTimeout, count = Some(settings.batchSize))
      )
      .flatMap { claimed =>
        if (claimed.messages.nonEmpty) Applicative[F].pure(claimed.messages)
        else
          reader.xReadGroup(
            streamConsumer,
            XReadOffsets.custom(">", streamKey),
            XReadGroupArgs(count = Some(settings.batchSize), block = Some(settings.blockTimeout))
          )
      }

  private def decode(
    groupId: String
  )(message: StreamMessage[String, String]): F[Option[ConsumerGroupRecord[String, A]]] =
    RedisData.from(message.body)(messagingTopic.jsonCodec) match {
      case Right(value) => Applicative[F].pure(Some(ConsumerGroupRecord(groupId, message.id.value, value)))

      case Left(exception) =>
        // Acknowledge the message, otherwise it would be claimed again forever
        logger
          .error[F](s"Unable to parse message id=${message.id.value} in stream=$streamKey", exception)
          .productR {
            retrying(s"Unable to acknowledge the unparsable message id=${message.id.value} in stream=$streamKey") {
              redisCommands.xAck(streamKey, groupId, message.id.value)
            }
          }
          .as(None)
    }

  override def commit[H[_]: Foldable: Functor](values: H[ConsumerGroupRecord[String, A]]): F[Unit] =
    values.toList
      .groupMap(_.groupId)(_.id)
      .toList
      .traverse_ { case (groupId, ids) => redisCommands.xAck(streamKey, groupId, ids: _*) }

  override def extractValue(ca: ConsumerGroupRecord[String, A]): A = ca.value
}

object RedisStreamSubscriber {
  final case class Settings(
    blockTimeout: FiniteDuration = 1 second,
    batchSize: Long = 100,
    // Mirrors Kafka's default max.poll.interval.ms
    redeliveryTimeout: FiniteDuration = 5 minutes,
    retryDelay: FiniteDuration = 1 second,
    maxRetryDelay: FiniteDuration = 30 seconds
  )

  /**
    * Deletes the consumers of group ARGV[1] on stream KEYS[1] that hold no pending messages and have been idle for
    * longer than ARGV[2] milliseconds, returning how many it deleted. A script, so that no consumer can be handed a
    * message between checking it and deleting it.
    */
  private val DeleteStaleConsumersScript: String =
    """
      |local deleted = 0
      |for _, consumer in ipairs(redis.call('XINFO', 'CONSUMERS', KEYS[1], ARGV[1])) do
      |  local fields = {}
      |  for index = 1, #consumer, 2 do
      |    fields[consumer[index]] = consumer[index + 1]
      |  end
      |  if fields['pending'] == 0 and fields['idle'] > tonumber(ARGV[2]) then
      |    redis.call('XGROUP', 'DELCONSUMER', KEYS[1], ARGV[1], fields['name'])
      |    deleted = deleted + 1
      |  end
      |end
      |return deleted
      |""".stripMargin

  def create[F[_]: Async, A: MessagingTopic](
    redisConfiguration: RedisConfiguration,
    settings: Settings = Settings()
  ): Resource[F, RedisStreamSubscriber[F, A]] =
    Redis[F].utf8(redisConfiguration.uri).map { redisCommands =>
      new RedisStreamSubscriber[F, A](redisCommands, Redis[F].utf8(redisConfiguration.uri), settings)
    }
}
