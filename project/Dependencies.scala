import sbt.*

object Dependencies
{
  val ScalaVersion = "2.13.18"
  val Http4sVersion = "0.23.33"
  val CirceVersion = "0.14.15"
  val FlywayVersion = "12.0.0"

  lazy val http4sDsl = "org.http4s" %% "http4s-dsl" % Http4sVersion

  lazy val http4sEmberServer = "org.http4s" %% "http4s-ember-server" % Http4sVersion

  lazy val http4sCirce = "org.http4s" %% "http4s-circe" % Http4sVersion

  lazy val http4sJdkHttpClient = "org.http4s" %% "http4s-jdk-http-client" % "0.10.0"

  lazy val catsEffect = "org.typelevel" %% "cats-effect" % "3.6.3"

  lazy val kafka = "org.apache.kafka" %% "kafka" % "8.1.1-ccs"

  lazy val fs2Kafka = "com.github.fd4s" %% "fs2-kafka" % "3.9.1"

  lazy val fs2KafkaVulkan = "com.github.fd4s" %% "fs2-kafka-vulcan" % "3.9.1"

  lazy val kafkaAvroSerializer = "io.confluent" % "kafka-avro-serializer" % "8.1.1"

  lazy val vulkanGeneric = "com.github.fd4s" %% "vulcan-generic" % "1.12.0"

  lazy val circeGeneric = "io.circe" %% "circe-generic" % CirceVersion

  lazy val circeParser = "io.circe" %% "circe-parser" % CirceVersion

  lazy val circeLiteral = "io.circe" %% "circe-literal" % CirceVersion

  lazy val enumeratum = "com.beachape" %% "enumeratum" % "1.9.4"

  lazy val shapeless = "com.chuusai" %% "shapeless" % "2.3.13"

  lazy val doobie = "org.tpolecat" %% "doobie-core" % "1.0.0-RC11"

  lazy val doobieHikari = "org.tpolecat" %% "doobie-hikari" % "1.0.0-RC11"

  lazy val jsoup = "org.jsoup" % "jsoup" % "1.22.1"

  lazy val jodaTime = "joda-time" % "joda-time" % "2.14.0"

  lazy val pureconfig = "com.github.pureconfig" %% "pureconfig" % "0.17.10"

  lazy val flywayCore = "org.flywaydb" % "flyway-core" % FlywayVersion

  lazy val flywayPostgresql = "org.flywaydb" % "flyway-database-postgresql"  % FlywayVersion

  lazy val postgresql = "org.postgresql" % "postgresql" % "42.7.9"

  lazy val h2 = "com.h2database" % "h2" % "2.4.240"

  lazy val apacheTika = "org.apache.tika" % "tika-core" % "3.2.3"

  lazy val redis4CatsEffects = "dev.profunktor" %% "redis4cats-effects" % "2.0.3"

  lazy val jbcrypt = "org.mindrot" % "jbcrypt" % "0.4"

  lazy val embeddedRedis = "com.github.kstyrc" % "embedded-redis" % "0.6"

  lazy val embeddedKafkaSchemaRegistry = "io.github.embeddedkafka" %% "embedded-kafka-schema-registry" % "8.1.0"

  lazy val testContainers = "org.testcontainers" % "testcontainers" % "2.0.3"

  lazy val kafkaTestContainer = "org.testcontainers" % "kafka" % "1.21.4"

  lazy val postgresqlTestContainer = "org.testcontainers" % "postgresql" % "1.21.4"

  lazy val redisTestContainer = "com.redis" % "testcontainers-redis" % "2.2.4"

  lazy val logbackClassic = "ch.qos.logback" % "logback-classic" % "1.5.28"

  lazy val logstashLogbackEncoder = "net.logstash.logback" % "logstash-logback-encoder" % "9.0"

  lazy val scalaLogging = "com.typesafe.scala-logging" %% "scala-logging" % "3.9.6"

  lazy val kindProjector = "org.typelevel" %% "kind-projector" % "0.13.4" cross CrossVersion.full

  lazy val betterMonadicFor = "com.olegpy" %% "better-monadic-for" % "0.3.1"

  lazy val scalaTest = "org.scalatest" %% "scalatest" % "3.2.19"

  lazy val scalaMock = "org.scalamock" %% "scalamock" % "7.5.5"

  lazy val pegdown = "org.pegdown" % "pegdown" % "1.6.0"

  lazy val sentry = "io.sentry" % "sentry" % "8.31.0"
}
