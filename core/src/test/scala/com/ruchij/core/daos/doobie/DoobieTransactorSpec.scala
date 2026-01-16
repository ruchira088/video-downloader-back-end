package com.ruchij.core.daos.doobie

import cats.effect.IO
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.migration.config.DatabaseConfiguration
import doobie.implicits._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class DoobieTransactorSpec extends AnyFlatSpec with Matchers {

  "DoobieTransactor.create" should "create a working transactor for H2 database" in runIO {
    val dbConfig = DatabaseConfiguration(
      "jdbc:h2:mem:test-transactor;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
      "",
      ""
    )

    DoobieTransactor.create[IO](dbConfig).use { transactor =>
      sql"SELECT 1".query[Int].unique.transact(transactor).map { result =>
        result mustBe 1
      }
    }
  }

  it should "create a transactor with virtual threads" in runIO {
    val dbConfig = DatabaseConfiguration(
      "jdbc:h2:mem:test-virtual-threads;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
      "",
      ""
    )

    DoobieTransactor.create[IO](dbConfig).use { transactor =>
      sql"SELECT 42".query[Int].unique.transact(transactor).map { result =>
        result mustBe 42
      }
    }
  }

  it should "support multiple queries" in runIO {
    val dbConfig = DatabaseConfiguration(
      "jdbc:h2:mem:test-multi-query;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
      "",
      ""
    )

    DoobieTransactor.create[IO](dbConfig).use { transactor =>
      for {
        _ <- sql"CREATE TABLE test_table (id INT, name VARCHAR(255))".update.run.transact(transactor)
        _ <- sql"INSERT INTO test_table VALUES (1, 'test')".update.run.transact(transactor)
        result <- sql"SELECT COUNT(*) FROM test_table".query[Int].unique.transact(transactor)
      } yield {
        result mustBe 1
      }
    }
  }
}
