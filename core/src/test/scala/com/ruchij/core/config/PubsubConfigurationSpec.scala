package com.ruchij.core.config

import com.ruchij.core.config.PubsubConfiguration.pubsubConfigurationConfigReader
import com.ruchij.core.messaging.PubSub.PubsubType
import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import pureconfig.ConfigSource

class PubsubConfigurationSpec extends AnyFlatSpec with Matchers {

  "PubSubConfiguration" should "parse Kafka configuration" in {
    val config = ConfigFactory.parseString(
      """
        |type = "Kafka"
        |kafka-configuration {
        |  prefix = "test"
        |  bootstrap-servers = "localhost:9092"
        |  schema-registry = "http://localhost:8081"
        |}
        |""".stripMargin
    )

    val result = ConfigSource.fromConfig(config).load[PubsubConfiguration]

    result.isRight mustBe true

    val pubSubConfig = result.toOption.get
    pubSubConfig.pubsubType mustBe PubsubType.Kafka
    pubSubConfig.kafkaConfiguration must not be empty
    pubSubConfig.redisConfiguration mustBe None
    pubSubConfig.databaseConfiguration mustBe None

    val kafkaConfig = pubSubConfig.kafkaConfiguration.get
    kafkaConfig.bootstrapServers mustBe "localhost:9092"
    kafkaConfig.schemaRegistry.renderString mustBe "http://localhost:8081"
  }

  it should "parse Redis configuration" in {
    val config = ConfigFactory.parseString(
      """
        |type = "Redis"
        |redis-configuration {
        |  hostname = "localhost"
        |  port = 6379
        |}
        |""".stripMargin
    )

    val result = ConfigSource.fromConfig(config).load[PubsubConfiguration]

    result.isRight mustBe true

    val pubSubConfig = result.toOption.get
    pubSubConfig.pubsubType mustBe PubsubType.Redis
    pubSubConfig.kafkaConfiguration mustBe None
    pubSubConfig.redisConfiguration must not be empty
    pubSubConfig.databaseConfiguration mustBe None

    val redisConfig = pubSubConfig.redisConfiguration.get
    redisConfig.hostname mustBe "localhost"
    redisConfig.port mustBe 6379
  }

  it should "parse Doobie configuration" in {
    val config = ConfigFactory.parseString(
      """
        |type = "Doobie"
        |database-configuration {
        |  url = "jdbc:postgresql://localhost:5432/testdb"
        |  user = "admin"
        |  password = "secret"
        |}
        |""".stripMargin
    )

    val result = ConfigSource.fromConfig(config).load[PubsubConfiguration]

    result.isRight mustBe true

    val pubSubConfig = result.toOption.get
    pubSubConfig.pubsubType mustBe PubsubType.Doobie
    pubSubConfig.kafkaConfiguration mustBe None
    pubSubConfig.redisConfiguration mustBe None
    pubSubConfig.databaseConfiguration must not be empty

    val dbConfig = pubSubConfig.databaseConfiguration.get
    dbConfig.url mustBe "jdbc:postgresql://localhost:5432/testdb"
    dbConfig.user mustBe "admin"
    dbConfig.password mustBe "secret"
  }

  it should "parse Redis configuration with password" in {
    val config = ConfigFactory.parseString(
      """
        |type = "Redis"
        |redis-configuration {
        |  hostname = "redis.example.com"
        |  port = 6380
        |  password = "redis-secret"
        |}
        |""".stripMargin
    )

    val result = ConfigSource.fromConfig(config).load[PubsubConfiguration]

    result.isRight mustBe true

    val redisConfig = result.toOption.get.redisConfiguration.get
    redisConfig.hostname mustBe "redis.example.com"
    redisConfig.port mustBe 6380
    redisConfig.password mustBe Some("redis-secret")
  }

  it should "fail when the type key is missing" in {
    val config = ConfigFactory.parseString(
      """
        |kafka-configuration {
        |  prefix = "test"
        |  bootstrap-servers = "localhost:9092"
        |  schema-registry = "http://localhost:8081"
        |}
        |""".stripMargin
    )

    val result = ConfigSource.fromConfig(config).load[PubsubConfiguration]
    result.isLeft mustBe true
  }

  it should "fail for an invalid type value" in {
    val config = ConfigFactory.parseString(
      """
        |type = "InvalidType"
        |""".stripMargin
    )

    val result = ConfigSource.fromConfig(config).load[PubsubConfiguration]
    result.isLeft mustBe true
  }

  it should "fail when the required nested configuration block is missing" in {
    val config = ConfigFactory.parseString(
      """
        |type = "Kafka"
        |""".stripMargin
    )

    val result = ConfigSource.fromConfig(config).load[PubsubConfiguration]
    result.isLeft mustBe true
  }

  it should "fail when Redis type is specified but redis-configuration is missing" in {
    val config = ConfigFactory.parseString(
      """
        |type = "Redis"
        |""".stripMargin
    )

    val result = ConfigSource.fromConfig(config).load[PubsubConfiguration]
    result.isLeft mustBe true
  }

  it should "fail when Doobie type is specified but database-configuration is missing" in {
    val config = ConfigFactory.parseString(
      """
        |type = "Doobie"
        |""".stripMargin
    )

    val result = ConfigSource.fromConfig(config).load[PubsubConfiguration]
    result.isLeft mustBe true
  }

  it should "only populate the configuration matching the selected type" in {
    val config = ConfigFactory.parseString(
      """
        |type = "Kafka"
        |kafka-configuration {
        |  prefix = "test"
        |  bootstrap-servers = "localhost:9092"
        |  schema-registry = "http://localhost:8081"
        |}
        |""".stripMargin
    )

    val result = ConfigSource.fromConfig(config).load[PubsubConfiguration]

    result.isRight mustBe true

    val pubSubConfig = result.toOption.get
    pubSubConfig.kafkaConfiguration.isDefined mustBe true
    pubSubConfig.redisConfiguration.isDefined mustBe false
    pubSubConfig.databaseConfiguration.isDefined mustBe false
  }
}
