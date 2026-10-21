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
import com.ruchij.migration.config.{AdminConfiguration, MigrationServiceConfiguration}
import doobie.ConnectionIO
import doobie.free.connection.{raiseError, delay => connectionDelay}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.sql.SQLTransientConnectionException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._
import scala.language.postfixOps

class DoobiePublisherSubscriberSpec extends AnyFlatSpec with Matchers with PublisherSubscriberSpec {

  private val daoResource: Resource[IO, (MessageDao[ConnectionIO], ConnectionIO ~> IO)] =
    PostgresContainer
      .create[IO]
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
}

/** Fails the polling query until `failures` reaches zero. */
class FlakyConsumerGroupDao(underlying: MessageConsumerGroupDao[ConnectionIO], failures: AtomicInteger)
    extends MessageConsumerGroupDao[ConnectionIO] {
  private def failIfRequired: ConnectionIO[Unit] =
    connectionDelay(failures.getAndUpdate(count => math.max(count - 1, 0))).flatMap { remaining =>
      if (remaining > 0) raiseError(new SQLTransientConnectionException("Simulated transient error"))
      else connectionDelay(())
    }

  override def createGroup(channel: String, groupId: String, createdAt: Instant): ConnectionIO[Int] =
    underlying.createGroup(channel, groupId, createdAt)

  override def lockGroup(channel: String, groupId: String): ConnectionIO[Option[Long]] =
    failIfRequired.flatMap(_ => underlying.lockGroup(channel, groupId))

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

  override def insertClaims(groupId: String, messageIds: List[Long], claimedAt: Instant): ConnectionIO[Int] =
    underlying.insertClaims(groupId, messageIds, claimedAt)

  override def renewClaims(groupId: String, messageIds: List[Long], claimedAt: Instant): ConnectionIO[Int] =
    underlying.renewClaims(groupId, messageIds, claimedAt)

  override def acknowledge(groupId: String, messageIds: List[Long]): ConnectionIO[Int] =
    underlying.acknowledge(groupId, messageIds)
}
