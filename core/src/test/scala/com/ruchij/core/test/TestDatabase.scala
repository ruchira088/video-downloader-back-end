package com.ruchij.core.test

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Resource}
import cats.~>
import com.ruchij.core.daos.doobie.DoobieTransactor
import com.ruchij.core.external.CoreResourcesProvider.ExternalServiceProviderOps
import com.ruchij.core.external.containers.PostgresContainer
import com.ruchij.core.external.embedded.EmbeddedCoreResourcesProvider
import com.ruchij.migration.config.DatabaseConfiguration
import doobie.ConnectionIO
import org.scalatest.{BeforeAndAfterAll, Suite}

import scala.concurrent.ExecutionContext

/**
  * Gives each test of a DAO spec a fresh, migrated database. A spec written against this runs on H2 and on
  * PostgreSQL by mixing in [[H2TestDatabase]] or [[PostgresTestDatabase]], since H2's PostgreSQL mode differs from
  * PostgreSQL in ways that only show on a real PostgreSQL database, such as locking and timestamp precision.
  */
trait TestDatabase { this: Suite =>
  protected def transactor: Resource[IO, ConnectionIO ~> IO]
}

trait H2TestDatabase extends TestDatabase { this: Suite =>
  override protected def transactor: Resource[IO, ConnectionIO ~> IO] =
    new EmbeddedCoreResourcesProvider[IO].transactor(implicitly, ExecutionContext.global)
}

/** Starts one PostgreSQL container for the suite, and copies a migrated database in it for each test. */
trait PostgresTestDatabase extends TestDatabase with BeforeAndAfterAll { this: Suite =>
  private var databasesAndRelease: (Resource[IO, DatabaseConfiguration], IO[Unit]) = _

  override protected def beforeAll(): Unit = {
    databasesAndRelease = PostgresContainer.migratedDatabases[IO].allocated.unsafeRunSync()
    super.beforeAll()
  }

  override protected def afterAll(): Unit =
    try super.afterAll()
    finally Option(databasesAndRelease).foreach { case (_, release) => release.unsafeRunSync() }

  override protected def transactor: Resource[IO, ConnectionIO ~> IO] =
    databasesAndRelease._1.flatMap(DoobieTransactor.create[IO]).map(_.trans)
}
