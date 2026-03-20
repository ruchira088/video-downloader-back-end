package com.ruchij.core.messaging.postgres

import cats.Id
import cats.effect.{IO, Resource}
import cats.~>
import com.ruchij.core.daos.doobie.DoobieTransactor
import com.ruchij.core.daos.messaging.{DoobieMessageDao, MessageDao}
import com.ruchij.core.external.containers.PostgresContainer
import com.ruchij.core.messaging.PublisherSubscriberSpec.TestMessage
import com.ruchij.core.messaging.doobie.{DoobiePublisher, DoobieSubscriber}
import com.ruchij.core.messaging.{Publisher, PublisherSubscriberSpec, Subscriber}
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.types.Clock
import com.ruchij.migration.MigrationApp
import com.ruchij.migration.config.{AdminConfiguration, MigrationServiceConfiguration}
import doobie.ConnectionIO
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.duration._
import scala.language.postfixOps

class DoobiePublisherSubscriberSpec extends AnyFlatSpec with Matchers with PublisherSubscriberSpec[Id] {

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

  override def resource: Resource[IO, (Publisher[IO, TestMessage], Subscriber[IO, Id, TestMessage])] =
    daoResource.map { case (messageDao, transaction) =>
      implicit val tx: ConnectionIO ~> IO = transaction

      val publisher = new DoobiePublisher[IO, ConnectionIO, TestMessage](messageDao)
      val subscriber = DoobieSubscriber.create[IO, ConnectionIO, TestMessage](messageDao, 500 milliseconds)
      (publisher: Publisher[IO, TestMessage], subscriber: Subscriber[IO, Id, TestMessage])
    }

  override def extractValue(ga: Id[TestMessage]): TestMessage = ga

  override def testTimeout: FiniteDuration = 30 seconds

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
