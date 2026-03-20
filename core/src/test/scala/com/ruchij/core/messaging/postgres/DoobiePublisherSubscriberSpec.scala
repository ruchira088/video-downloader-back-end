package com.ruchij.core.messaging.postgres

import cats.Id
import cats.effect.{IO, Resource}
import cats.~>
import com.ruchij.core.daos.doobie.DoobieTransactor
import com.ruchij.core.daos.messaging.DoobieMessageDao
import com.ruchij.core.external.containers.PostgresContainer
import com.ruchij.core.messaging.PublisherSubscriberSpec.TestMessage
import com.ruchij.core.messaging.doobie.{DoobiePublisher, DoobieSubscriber}
import com.ruchij.core.messaging.{Publisher, PublisherSubscriberSpec, Subscriber}
import com.ruchij.migration.MigrationApp
import com.ruchij.migration.config.{AdminConfiguration, MigrationServiceConfiguration}
import doobie.ConnectionIO
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.duration._
import scala.language.postfixOps

class DoobiePublisherSubscriberSpec extends AnyFlatSpec with Matchers with PublisherSubscriberSpec[Id] {

  override def resource: Resource[IO, (Publisher[IO, TestMessage], Subscriber[IO, Id, TestMessage])] =
    PostgresContainer
      .create[IO]
      .evalTap { dbConfig =>
        MigrationApp.migration[IO](MigrationServiceConfiguration(dbConfig, AdminConfiguration("dummy-hash"))).void
      }
      .flatMap { dbConfig =>
        DoobieTransactor.create[IO](dbConfig).map { transactor =>
          implicit val transaction: ConnectionIO ~> IO = transactor.trans

          val publisher = new DoobiePublisher[IO, ConnectionIO, TestMessage](DoobieMessageDao)
          val subscriber = DoobieSubscriber.create[IO, ConnectionIO, TestMessage](DoobieMessageDao, 500 milliseconds)
          (publisher: Publisher[IO, TestMessage], subscriber: Subscriber[IO, Id, TestMessage])
        }
      }

  override def extractValue(ga: Id[TestMessage]): TestMessage = ga

  override def testTimeout: FiniteDuration = 30 seconds
}
