package com.ruchij.core.messaging.postgres

import cats.Id
import cats.effect.{IO, Resource}
import com.ruchij.core.daos.doobie.DoobieTransactor
import com.ruchij.core.external.containers.PostgresContainer
import com.ruchij.core.messaging.PublisherSubscriberSpec.TestMessage
import com.ruchij.core.messaging.{Publisher, PublisherSubscriberSpec, Subscriber}
import com.ruchij.migration.MigrationApp
import com.ruchij.migration.config.{AdminConfiguration, MigrationServiceConfiguration}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.duration._
import scala.language.postfixOps

class PostgresPublisherSubscriberSpec extends AnyFlatSpec with Matchers with PublisherSubscriberSpec[Id] {

  override def resource: Resource[IO, (Publisher[IO, TestMessage], Subscriber[IO, Id, TestMessage])] =
    PostgresContainer
      .create[IO]
      .evalTap { dbConfig =>
        MigrationApp.migration[IO](MigrationServiceConfiguration(dbConfig, AdminConfiguration("dummy-hash"))).void
      }
      .flatMap { dbConfig =>
        DoobieTransactor.create[IO](dbConfig).flatMap { transactor =>
          PostgresSubscriber
            .create[IO, TestMessage](dbConfig, transactor)
            .map { subscriber =>
              val publisher = PostgresPublisher.create[IO, TestMessage](transactor)
              (publisher: Publisher[IO, TestMessage], subscriber: Subscriber[IO, Id, TestMessage])
            }
        }
      }

  override def extractValue(ga: Id[TestMessage]): TestMessage = ga

  override def testTimeout: FiniteDuration = 30 seconds
}
