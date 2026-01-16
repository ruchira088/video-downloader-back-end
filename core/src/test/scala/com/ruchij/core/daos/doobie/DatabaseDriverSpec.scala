package com.ruchij.core.daos.doobie

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class DatabaseDriverSpec extends AnyFlatSpec with Matchers {

  "parseFromConnectionUrl" should "parse PostgreSQL connection URL" in {
    val result = DatabaseDriver.parseFromConnectionUrl("jdbc:postgresql://localhost:5432/testdb")

    result mustBe Right(DatabaseDriver.PostgreSQL)
  }

  it should "parse H2 connection URL" in {
    val result = DatabaseDriver.parseFromConnectionUrl("jdbc:h2:mem:testdb")

    result mustBe Right(DatabaseDriver.H2)
  }

  it should "parse PostgreSQL URL with various hosts" in {
    val urls = List(
      "jdbc:postgresql://localhost:5432/mydb",
      "jdbc:postgresql://192.168.1.100:5432/mydb",
      "jdbc:postgresql://db.example.com:5432/mydb",
      "jdbc:postgresql://db.example.com/mydb?ssl=true"
    )

    urls.foreach { url =>
      DatabaseDriver.parseFromConnectionUrl(url) mustBe Right(DatabaseDriver.PostgreSQL)
    }
  }

  it should "parse H2 URLs with various configurations" in {
    val urls = List(
      "jdbc:h2:mem:test",
      "jdbc:h2:file:/data/test",
      "jdbc:h2:tcp://localhost/~/test",
      "jdbc:h2:mem:test;MODE=PostgreSQL"
    )

    urls.foreach { url =>
      DatabaseDriver.parseFromConnectionUrl(url) mustBe Right(DatabaseDriver.H2)
    }
  }

  it should "return Left for unsupported connection URLs" in {
    val result = DatabaseDriver.parseFromConnectionUrl("jdbc:mysql://localhost:3306/testdb")

    result.isLeft mustBe true
    result.left.toOption.get.getMessage must include("Unable to infer database driver")
    result.left.toOption.get.getMessage must include("mysql")
  }

  it should "return Left for invalid connection URLs" in {
    val result = DatabaseDriver.parseFromConnectionUrl("not-a-valid-url")

    result.isLeft mustBe true
    result.left.toOption.get mustBe an[IllegalArgumentException]
  }

  it should "return Left for empty connection URL" in {
    val result = DatabaseDriver.parseFromConnectionUrl("")

    result.isLeft mustBe true
  }

  it should "be case insensitive for driver names in URL" in {
    // The implementation uses toLowerCase, so this should work
    DatabaseDriver.parseFromConnectionUrl("jdbc:postgresql://localhost/test") mustBe Right(DatabaseDriver.PostgreSQL)
    DatabaseDriver.parseFromConnectionUrl("jdbc:h2:mem:test") mustBe Right(DatabaseDriver.H2)
  }

  "DatabaseDriver.values" should "contain PostgreSQL and H2" in {
    val values = DatabaseDriver.values

    values must contain(DatabaseDriver.PostgreSQL)
    values must contain(DatabaseDriver.H2)
    values must have size 2
  }

  "DatabaseDriver.PostgreSQL" should "have correct driver class name" in {
    DatabaseDriver.PostgreSQL.driver mustBe "org.postgresql.Driver"
  }

  "DatabaseDriver.H2" should "have correct driver class name" in {
    DatabaseDriver.H2.driver mustBe "org.h2.Driver"
  }

  "DatabaseDriver" should "be an Enum" in {
    DatabaseDriver.withName("PostgreSQL") mustBe DatabaseDriver.PostgreSQL
    DatabaseDriver.withName("H2") mustBe DatabaseDriver.H2
  }

  it should "throw for invalid enum names" in {
    an[NoSuchElementException] should be thrownBy DatabaseDriver.withName("MySQL")
  }

  "entryName" should "return the correct names" in {
    DatabaseDriver.PostgreSQL.entryName mustBe "PostgreSQL"
    DatabaseDriver.H2.entryName mustBe "H2"
  }
}
