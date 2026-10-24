package com.ruchij.core.test

import cats.implicits._
import com.ruchij.core.test.IOSupport.runIO
import doobie.implicits._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class PostgresTestDatabaseSpec extends AnyFlatSpec with Matchers with PostgresTestDatabase {

  "PostgresTestDatabase" should "connect to PostgreSQL, not to H2 in PostgreSQL mode" in runIO {
    transactor.use { transaction =>
      transaction(sql"SELECT version()".query[String].unique).map { version =>
        version must startWith("PostgreSQL")
      }
    }
  }

  it should "give each use a migrated database that the earlier uses have not written to" in runIO {
    val countFileResources = sql"SELECT COUNT(*) FROM file_resource".query[Int].unique

    for {
      databaseName <- transactor.use { transaction =>
        transaction {
          sql"""INSERT INTO file_resource (id, created_at, path, media_type, size)
                VALUES ('file-id', NOW(), '/path', 'image/jpeg', 1)""".update.run
            .productR(sql"SELECT current_database()".query[String].unique)
        }
      }

      (otherDatabaseName, fileResourceCount) <- transactor.use { transaction =>
        transaction(sql"SELECT current_database()".query[String].unique.product(countFileResources))
      }

      // The copy is dropped once its test is done, so copies don't pile up in the container
      remainingDatabases <- transactor.use { transaction =>
        transaction(sql"SELECT COUNT(*) FROM pg_database WHERE datname = $databaseName".query[Int].unique)
      }
    } yield {
      otherDatabaseName must not be databaseName
      fileResourceCount mustBe 0
      remainingDatabases mustBe 0
    }
  }

  it should "copy the admin user created by the migrations" in runIO {
    transactor.use { transaction =>
      transaction(sql"SELECT COUNT(*) FROM api_user".query[Int].unique).map(_ must be > 0)
    }
  }
}
