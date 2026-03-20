package com.ruchij.core.kv

import cats.effect.{IO, Resource}
import cats.~>
import com.ruchij.core.daos.doobie.DoobieTransactor
import com.ruchij.core.daos.keyvalue.{DoobieKeyValueDao, KeyValueDao}
import com.ruchij.core.external.containers.PostgresContainer
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.types.Clock
import com.ruchij.migration.MigrationApp
import com.ruchij.migration.config.{AdminConfiguration, MigrationServiceConfiguration}
import doobie.ConnectionIO
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.duration._
import scala.language.postfixOps

class DoobieKeyValueStoreSpec extends AnyFlatSpec with Matchers {

  private val daoResource: Resource[IO, (KeyValueDao[ConnectionIO], ConnectionIO ~> IO)] =
    PostgresContainer.create[IO]
      .evalTap { dbConfig =>
        MigrationApp.migration[IO](MigrationServiceConfiguration(dbConfig, AdminConfiguration("dummy-hash"))).void
      }
      .flatMap { dbConfig =>
        DoobieTransactor.create[IO](dbConfig).map { transactor =>
          (DoobieKeyValueDao: KeyValueDao[ConnectionIO], transactor.trans: ConnectionIO ~> IO)
        }
      }

  private val resource: Resource[IO, DoobieKeyValueStore[IO, ConnectionIO]] =
    daoResource.map { case (keyValueDao, transaction) =>
      implicit val tx: ConnectionIO ~> IO = transaction
      new DoobieKeyValueStore[IO, ConnectionIO](keyValueDao)
    }

  "put and get" should "store and retrieve a value" in runIO {
    resource.use { kvStore =>
      for {
        _ <- kvStore.put("test-key", "test-value", None)
        result <- kvStore.get[String, String]("test-key")
        _ <- IO.delay { result mustBe Some("test-value") }
      } yield ()
    }
  }

  "get" should "return None for a non-existent key" in runIO {
    resource.use { kvStore =>
      for {
        result <- kvStore.get[String, String]("missing-key")
        _ <- IO.delay { result mustBe None }
      } yield ()
    }
  }

  "put" should "upsert when the same key is inserted twice" in runIO {
    resource.use { kvStore =>
      for {
        _ <- kvStore.put("upsert-key", "value-1", None)
        _ <- kvStore.put("upsert-key", "value-2", None)
        result <- kvStore.get[String, String]("upsert-key")
        _ <- IO.delay { result mustBe Some("value-2") }
      } yield ()
    }
  }

  "put without TTL" should "persist the value indefinitely" in runIO {
    resource.use { kvStore =>
      for {
        _ <- kvStore.put("no-ttl-key", "persistent-value", None)
        _ <- IO.sleep(100 milliseconds)
        result <- kvStore.get[String, String]("no-ttl-key")
        _ <- IO.delay { result mustBe Some("persistent-value") }
      } yield ()
    }
  }

  "put with TTL" should "not return expired values" in runIO {
    resource.use { kvStore =>
      for {
        _ <- kvStore.put("ttl-key", "ttl-value", Some(1 millisecond))
        _ <- IO.sleep(50 milliseconds)
        result <- kvStore.get[String, String]("ttl-key")
        _ <- IO.delay { result mustBe None }
      } yield ()
    }
  }

  "remove" should "delete the key" in runIO {
    resource.use { kvStore =>
      for {
        _ <- kvStore.put("remove-key", "remove-value", None)
        _ <- kvStore.remove[String]("remove-key")
        result <- kvStore.get[String, String]("remove-key")
        _ <- IO.delay { result mustBe None }
      } yield ()
    }
  }

  "deleteExpiredKeys" should "remove expired entries and keep non-expired ones" in runIO {
    daoResource.use { case (keyValueDao, transaction) =>
      implicit val tx: ConnectionIO ~> IO = transaction

      val kvStore = new DoobieKeyValueStore[IO, ConnectionIO](keyValueDao)

      for {
        _ <- kvStore.put("expired-key", "expired-value", Some(1 millisecond))
        _ <- kvStore.put("valid-key", "valid-value", None)
        _ <- IO.sleep(50 milliseconds)

        now <- Clock[IO].timestamp
        deleted <- tx(keyValueDao.deleteExpiredKeys(now))

        expiredResult <- kvStore.get[String, String]("expired-key")
        validResult <- kvStore.get[String, String]("valid-key")

        _ <- IO.delay {
          deleted mustBe 1
          expiredResult mustBe None
          validResult mustBe Some("valid-value")
        }
      } yield ()
    }
  }

  it should "return 0 when there are no expired keys" in runIO {
    daoResource.use { case (keyValueDao, transaction) =>
      implicit val tx: ConnectionIO ~> IO = transaction

      val kvStore = new DoobieKeyValueStore[IO, ConnectionIO](keyValueDao)

      for {
        _ <- kvStore.put("permanent-key", "permanent-value", None)

        now <- Clock[IO].timestamp
        deleted <- tx(keyValueDao.deleteExpiredKeys(now))

        _ <- IO.delay { deleted mustBe 0 }
      } yield ()
    }
  }
}
