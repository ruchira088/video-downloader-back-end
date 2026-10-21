package com.ruchij.core.messaging.postgres

import cats.effect.{IO, Resource}
import cats.implicits._
import cats.~>
import com.ruchij.core.daos.doobie.DoobieTransactor
import com.ruchij.core.daos.messaging.{
  DoobieMessageConsumerGroupDao,
  DoobieMessageDao,
  MessageConsumerGroupDao,
  MessageDao
}
import com.ruchij.core.external.containers.PostgresContainer
import com.ruchij.core.messaging.PublisherSubscriberSpec.TestMessage
import com.ruchij.core.messaging.PublisherSubscriberSpec.TestMessage._
import com.ruchij.core.messaging.db.{DoobiePublisher, DoobieSubscriber}
import com.ruchij.core.messaging.{Publisher, PublisherSubscriberSpec, Subscriber}
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.types.Clock
import com.ruchij.migration.MigrationApp
import com.ruchij.migration.config.{AdminConfiguration, DatabaseConfiguration, MigrationServiceConfiguration}
import doobie.ConnectionIO
import doobie.free.connection.{raiseError, delay => connectionDelay}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.sql.SQLTransientConnectionException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._
import scala.language.postfixOps

class PostgresDoobiePublisherSubscriberSpec extends DoobiePublisherSubscriberSpec(PostgresContainer.create[IO])

class H2DoobiePublisherSubscriberSpec
    extends DoobiePublisherSubscriberSpec(
      Resource.eval(IO.delay(UUID.randomUUID().toString.take(8))).map { databaseName =>
        DatabaseConfiguration(
          s"jdbc:h2:mem:doobie-messaging-$databaseName;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
          "",
          ""
        )
      }
    )

abstract class DoobiePublisherSubscriberSpec(databaseConfiguration: Resource[IO, DatabaseConfiguration])
    extends AnyFlatSpec
    with Matchers
    with PublisherSubscriberSpec {

  private val channel: String = testMessageMessagingTopic.name

  private val daoResource: Resource[IO, (MessageDao[ConnectionIO], ConnectionIO ~> IO)] =
    databaseConfiguration
      .evalTap { dbConfig =>
        MigrationApp.migration[IO](MigrationServiceConfiguration(dbConfig, AdminConfiguration("dummy-hash"))).void
      }
      .flatMap { dbConfig =>
        DoobieTransactor.create[IO](dbConfig).map { transactor =>
          (DoobieMessageDao: MessageDao[ConnectionIO], transactor.trans: ConnectionIO ~> IO)
        }
      }

  override def resource: Resource[IO, (Publisher[IO, TestMessage], Subscriber[IO, TestMessage])] =
    daoResource.map { case (messageDao, transaction) =>
      implicit val tx: ConnectionIO ~> IO = transaction

      val publisher = new DoobiePublisher[IO, ConnectionIO, TestMessage](messageDao)
      val subscriber =
        DoobieSubscriber.create[IO, ConnectionIO, TestMessage](DoobieMessageConsumerGroupDao, 500 milliseconds)
      (publisher: Publisher[IO, TestMessage], subscriber: Subscriber[IO, TestMessage])
    }

  override def testTimeout: FiniteDuration = 30 seconds

  "DoobieSubscriber" should "deliver a row that commits after a row with a higher id" in runIO {
    daoResource.use {
      case (messageDao, transaction) =>
        implicit val tx: ConnectionIO ~> IO = transaction

        val publisher = new DoobiePublisher[IO, ConnectionIO, TestMessage](messageDao)
        val subscriber =
          DoobieSubscriber.create[IO, ConnectionIO, TestMessage](DoobieMessageConsumerGroupDao, 200 milliseconds)

        for {
          receivedFiber <- subscriber
            .subscribe("late-commit-group")
            .map(subscriber.extractValue)
            .take(2)
            .compile
            .toList
            .timeout(testTimeout)
            .start

          _ <- IO.sleep(2 seconds)

          now <- Clock[IO].timestamp
          // Takes the lower id, but only commits after the next message has been published and delivered
          slowInsert <- tx {
            messageDao
              .insert(testMessageMessagingTopic.name, testMessageCodec(TestMessage("late", 0)).noSpaces, now)
              .flatMap(result => connectionDelay(Thread.sleep(3000)).as(result))
          }.start

          _ <- IO.sleep(500 milliseconds)
          _ <- publisher.publishOne(TestMessage("early", 1))
          _ <- slowInsert.joinWithNever

          received <- receivedFiber.joinWithNever

          _ <- IO.delay {
            received.map(_.name).toSet mustBe Set("late", "early")
          }
        } yield ()
    }
  }

  it should "keep polling after a transient database error" in runIO {
    daoResource.use {
      case (messageDao, transaction) =>
        implicit val tx: ConnectionIO ~> IO = transaction

        for {
          failures <- IO.delay(new AtomicInteger(3))
          flakyConsumerGroupDao = new FlakyConsumerGroupDao(DoobieMessageConsumerGroupDao, failures)

          publisher = new DoobiePublisher[IO, ConnectionIO, TestMessage](messageDao)
          subscriber = DoobieSubscriber.create[IO, ConnectionIO, TestMessage](flakyConsumerGroupDao, 200 milliseconds)

          receivedFiber <- subscriber
            .subscribe("flaky-group")
            .map(subscriber.extractValue)
            .take(1)
            .compile
            .toList
            .timeout(testTimeout)
            .start

          _ <- IO.sleep(2 seconds)
          _ <- publisher.publishOne(TestMessage("after-errors", 7))

          received <- receivedFiber.joinWithNever
          remainingFailures <- IO.delay(failures.get())

          _ <- IO.delay {
            remainingFailures mustBe 0
            received mustBe List(TestMessage("after-errors", 7))
          }
        } yield ()
    }
  }

  it should "redeliver a message that was received but never committed" in runIO {
    daoResource.use {
      case (messageDao, transaction) =>
        implicit val tx: ConnectionIO ~> IO = transaction

        val publisher = new DoobiePublisher[IO, ConnectionIO, TestMessage](messageDao)
        val subscriber =
          DoobieSubscriber.create[IO, ConnectionIO, TestMessage](
            DoobieMessageConsumerGroupDao,
            200 milliseconds,
            redeliveryTimeout = 1 second
          )

        for {
          uncommittedFiber <- subscriber
            .subscribe("redelivery-group")
            .map(subscriber.extractValue)
            .take(1)
            .compile
            .toList
            .timeout(testTimeout)
            .start

          _ <- IO.sleep(2 seconds)
          _ <- publisher.publishOne(TestMessage("redelivered", 3))
          uncommitted <- uncommittedFiber.joinWithNever

          redelivered <- subscriber
            .subscribe("redelivery-group")
            .evalMap(record => subscriber.commit(List(record)).as(subscriber.extractValue(record)))
            .take(1)
            .compile
            .toList
            .timeout(testTimeout)

          _ <- IO.delay {
            uncommitted mustBe List(TestMessage("redelivered", 3))
            redelivered mustBe List(TestMessage("redelivered", 3))
          }
        } yield ()
    }
  }

  it should "advance the group's start id past the messages it has acknowledged" in runIO {
    daoResource.use {
      case (messageDao, transaction) =>
        implicit val tx: ConnectionIO ~> IO = transaction

        val publisher = new DoobiePublisher[IO, ConnectionIO, TestMessage](messageDao)
        val subscriber =
          DoobieSubscriber.create[IO, ConnectionIO, TestMessage](
            DoobieMessageConsumerGroupDao,
            200 milliseconds,
            lateCommitWindow = 0 seconds
          )

        val startId: IO[Option[Long]] = tx(DoobieMessageConsumerGroupDao.lockGroup(channel, "low-water-group"))

        for {
          receivedFiber <- subscriber
            .subscribe("low-water-group")
            .evalMap { record =>
              // Messages 3 and 4 are received but never acknowledged
              (if (record.value.index < 3) subscriber.commit(List(record)) else IO.unit).as(record.value)
            }
            .take(5)
            .compile
            .drain
            .timeout(testTimeout)
            .start

          _ <- IO.sleep(2 seconds)
          _ <- publishAll(publisher, "low-water", 0 until 5)
          _ <- receivedFiber.joinWithNever
          ids <- tx(messageDao.findAfter(channel, 0)).map(_.map { case (id, _) => id })

          // Polling advances the start id, so keep a subscription that acknowledges nothing running
          advancedStartId <- subscriber.subscribe("low-water-group").compile.drain.background.use { _ =>
            waitUntil(startId.map(_.contains(ids(2)))).timeout(10 seconds) *> IO.sleep(1 second) *> startId
          }

          _ <- IO.delay {
            advancedStartId mustBe Some(ids(2))
          }
        } yield ()
    }
  }

  it should "keep consuming after failing to acknowledge a message it cannot decode" in runIO {
    daoResource.use {
      case (messageDao, transaction) =>
        implicit val tx: ConnectionIO ~> IO = transaction

        for {
          acknowledgeFailures <- IO.delay(new AtomicInteger(3))
          flakyConsumerGroupDao =
            new FlakyConsumerGroupDao(DoobieMessageConsumerGroupDao, new AtomicInteger(0), acknowledgeFailures)

          publisher = new DoobiePublisher[IO, ConnectionIO, TestMessage](messageDao)
          subscriber = DoobieSubscriber.create[IO, ConnectionIO, TestMessage](flakyConsumerGroupDao, 200 milliseconds)

          receivedFiber <- subscriber
            .subscribe("undecodable-group")
            .map(subscriber.extractValue)
            .take(1)
            .compile
            .toList
            .timeout(testTimeout)
            .start

          _ <- IO.sleep(2 seconds)
          now <- Clock[IO].timestamp
          _ <- tx(messageDao.insert(channel, "not-json", now))
          _ <- publisher.publishOne(TestMessage("decodable", 1))

          received <- receivedFiber.joinWithNever
          remainingFailures <- IO.delay(acknowledgeFailures.get())

          _ <- IO.delay {
            remainingFailures mustBe 0
            received mustBe List(TestMessage("decodable", 1))
          }
        } yield ()
    }
  }

  it should "release the messages a subscription claimed but never acknowledged once it ends" in runIO {
    daoResource.use {
      case (messageDao, transaction) =>
        implicit val tx: ConnectionIO ~> IO = transaction

        val publisher = new DoobiePublisher[IO, ConnectionIO, TestMessage](messageDao)
        // Uses the default redelivery timeout of 5 minutes, which is far longer than the test timeout
        val subscriber =
          DoobieSubscriber.create[IO, ConnectionIO, TestMessage](DoobieMessageConsumerGroupDao, 200 milliseconds)

        for {
          firstFiber <- subscriber
            .subscribe("release-group")
            .map(subscriber.extractValue)
            .take(1)
            .compile
            .toList
            .timeout(testTimeout)
            .start

          _ <- IO.sleep(2 seconds)
          _ <- publishAll(publisher, "release", 0 until 3)
          first <- firstFiber.joinWithNever

          second <- subscriber
            .subscribe("release-group")
            .evalMap(record => subscriber.commit(List(record)).as(subscriber.extractValue(record)))
            .take(3)
            .compile
            .toList
            .timeout(testTimeout)

          _ <- IO.delay {
            first.map(_.index) mustBe List(0)
            second.map(_.index) mustBe List(0, 1, 2)
          }
        } yield ()
    }
  }

  "deleteBefore" should "remove messages created before the given timestamp" in runIO {
    daoResource.use { case (messageDao, transaction) =>
      implicit val tx: ConnectionIO ~> IO = transaction

      for {
        now <- Clock[IO].timestamp

        _ <- tx(messageDao.insert("test-channel", """{"name":"old","index":1}""", now.minusSeconds(60)))
        _ <- tx(messageDao.insert("test-channel", """{"name":"new","index":2}""", now))

        deleted <- tx(messageDao.deleteBefore(now.minusSeconds(30)))

        remaining <- tx(messageDao.findAfter("test-channel", 0))

        _ <- IO.delay {
          deleted mustBe 1
          remaining must have length 1
          remaining.head._2 mustBe """{"name":"new","index":2}"""
        }
      } yield ()
    }
  }

  it should "return 0 when no messages are older than the timestamp" in runIO {
    daoResource.use { case (messageDao, transaction) =>
      implicit val tx: ConnectionIO ~> IO = transaction

      for {
        now <- Clock[IO].timestamp

        _ <- tx(messageDao.insert("test-channel", """{"name":"recent","index":1}""", now))

        deleted <- tx(messageDao.deleteBefore(now.minusSeconds(60)))

        _ <- IO.delay { deleted mustBe 0 }
      } yield ()
    }
  }

  it should "keep messages that a consumer group of the channel hasn't acknowledged" in runIO {
    daoResource.use { case (messageDao, transaction) =>
      implicit val tx: ConnectionIO ~> IO = transaction

      for {
        now <- Clock[IO].timestamp
        _ <- tx(DoobieMessageConsumerGroupDao.createGroup("test-channel", "group", now))
        _ <- tx(DoobieMessageConsumerGroupDao.createGroup("other-channel", "other-group", now))

        _ <- tx(messageDao.insert("test-channel", """{"name":"unacknowledged","index":1}""", now.minusSeconds(60)))
        deletedBeforeAcknowledgement <- tx(messageDao.deleteBefore(now.minusSeconds(30)))

        ids <- tx(messageDao.findAfter("test-channel", 0)).map(_.map { case (id, _) => id })
        _ <- tx(DoobieMessageConsumerGroupDao.insertClaims("group", "consumer", ids, now))
        _ <- tx(DoobieMessageConsumerGroupDao.acknowledge("group", ids))
        deletedAfterAcknowledgement <- tx(messageDao.deleteBefore(now.minusSeconds(30)))

        _ <- IO.delay {
          deletedBeforeAcknowledgement mustBe 0
          deletedAfterAcknowledgement mustBe 1
        }
      } yield ()
    }
  }

  it should "remove unacknowledged messages older than the hard retention" in runIO {
    daoResource.use { case (messageDao, transaction) =>
      implicit val tx: ConnectionIO ~> IO = transaction

      for {
        now <- Clock[IO].timestamp
        _ <- tx(DoobieMessageConsumerGroupDao.createGroup("test-channel", "group", now))
        _ <- tx(messageDao.insert("test-channel", """{"name":"ancient","index":1}""", now.minus(8, ChronoUnit.DAYS)))
        _ <- tx(messageDao.insert("test-channel", """{"name":"recent","index":2}""", now.minusSeconds(60)))

        deleted <- tx(messageDao.deleteBefore(now.minusSeconds(30)))
        remaining <- tx(messageDao.findAfter("test-channel", 0))

        _ <- IO.delay {
          deleted mustBe 1
          remaining.map { case (_, payload) => payload } mustBe List("""{"name":"recent","index":2}""")
        }
      } yield ()
    }
  }

  it should "expire idle consumer groups, so that they stop holding back deletion" in runIO {
    daoResource.use { case (messageDao, transaction) =>
      implicit val tx: ConnectionIO ~> IO = transaction

      for {
        now <- Clock[IO].timestamp
        _ <- tx(DoobieMessageConsumerGroupDao.createGroup("test-channel", "idle-group", now.minus(2, ChronoUnit.DAYS)))
        _ <- tx(messageDao.insert("test-channel", """{"name":"unacknowledged","index":1}""", now.minusSeconds(60)))

        deleted <- tx(messageDao.deleteBefore(now.minusSeconds(30)))
        idleGroup <- tx(DoobieMessageConsumerGroupDao.lockGroup("test-channel", "idle-group"))

        _ <- IO.delay {
          deleted mustBe 1
          idleGroup mustBe None
        }
      } yield ()
    }
  }
}

/** Fails locking the group until `lockFailures` reaches zero, and acknowledging until `acknowledgeFailures` does. */
class FlakyConsumerGroupDao(
  underlying: MessageConsumerGroupDao[ConnectionIO],
  lockFailures: AtomicInteger,
  acknowledgeFailures: AtomicInteger = new AtomicInteger(0)
) extends MessageConsumerGroupDao[ConnectionIO] {
  private def failIfRequired(failures: AtomicInteger): ConnectionIO[Unit] =
    connectionDelay(failures.getAndUpdate(count => math.max(count - 1, 0))).flatMap { remaining =>
      if (remaining > 0) raiseError(new SQLTransientConnectionException("Simulated transient error"))
      else connectionDelay(())
    }

  override def createGroup(channel: String, groupId: String, createdAt: Instant): ConnectionIO[Int] =
    underlying.createGroup(channel, groupId, createdAt)

  override def lockGroup(channel: String, groupId: String): ConnectionIO[Option[Long]] =
    failIfRequired(lockFailures).flatMap(_ => underlying.lockGroup(channel, groupId))

  override def findUnclaimed(
    channel: String,
    groupId: String,
    startId: Long,
    limit: Int
  ): ConnectionIO[List[(Long, String)]] =
    underlying.findUnclaimed(channel, groupId, startId, limit)

  override def findExpiredClaims(
    channel: String,
    groupId: String,
    claimedBefore: Instant,
    limit: Int
  ): ConnectionIO[List[(Long, String)]] =
    underlying.findExpiredClaims(channel, groupId, claimedBefore, limit)

  override def markPolled(
    channel: String,
    groupId: String,
    polledAt: Instant,
    staleBefore: Instant
  ): ConnectionIO[Int] =
    underlying.markPolled(channel, groupId, polledAt, staleBefore)

  override def advanceStartId(
    channel: String,
    groupId: String,
    startId: Long,
    createdBefore: Instant
  ): ConnectionIO[Option[Long]] =
    underlying.advanceStartId(channel, groupId, startId, createdBefore)

  override def insertClaims(
    groupId: String,
    consumerId: String,
    messageIds: List[Long],
    claimedAt: Instant
  ): ConnectionIO[Int] =
    underlying.insertClaims(groupId, consumerId, messageIds, claimedAt)

  override def renewClaims(
    groupId: String,
    consumerId: String,
    messageIds: List[Long],
    claimedAt: Instant
  ): ConnectionIO[Int] =
    underlying.renewClaims(groupId, consumerId, messageIds, claimedAt)

  override def releaseClaims(groupId: String, consumerId: String): ConnectionIO[Int] =
    underlying.releaseClaims(groupId, consumerId)

  override def acknowledge(groupId: String, messageIds: List[Long]): ConnectionIO[Int] =
    failIfRequired(acknowledgeFailures).flatMap(_ => underlying.acknowledge(groupId, messageIds))
}
