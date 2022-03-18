import sbt._

object Dependencies
{
  val ScalaVersion = "2.13.8"
  val Http4sVersion = "0.23.10"
  val CirceVersion = "0.14.1"
  val TestContainersVersion = "1.16.3"

  lazy val http4sDsl = "org.http4s" %% "http4s-dsl" % Http4sVersion

  lazy val http4sBlazeServer = "org.http4s" %% "http4s-blaze-server" % Http4sVersion

  lazy val http4sAsyncHttpClient = "org.http4s" %% "http4s-async-http-client" % Http4sVersion

  lazy val http4sCirce = "org.http4s" %% "http4s-circe" % Http4sVersion

  lazy val catsEffect = "org.typelevel" %% "cats-effect" % "3.3.7"

  lazy val fs2Io = "co.fs2" %% "fs2-io" % "3.2.5"

  lazy val fs2Kafka = "com.github.fd4s" %% "fs2-kafka" % "2.4.0"

  lazy val fs2KafkaVulkan = "com.github.fd4s" %% "fs2-kafka-vulcan" % "2.4.0"

  lazy val kafkaAvroSerializer = "io.confluent" % "kafka-avro-serializer" % "7.0.1"

  lazy val vulkanGeneric = "com.github.fd4s" %% "vulcan-generic" % "1.8.0"

  lazy val circeGeneric = "io.circe" %% "circe-generic" % CirceVersion

  lazy val circeParser = "io.circe" %% "circe-parser" % CirceVersion

  lazy val circeLiteral = "io.circe" %% "circe-literal" % CirceVersion

  lazy val enumeratum = "com.beachape" %% "enumeratum" % "1.7.0"

  lazy val shapeless = "com.chuusai" %% "shapeless" % "2.3.8"

  lazy val doobie = "org.tpolecat" %% "doobie-core" % "1.0.0-RC2"

  lazy val doobieHikari = "org.tpolecat" %% "doobie-hikari" % "1.0.0-RC2"

  lazy val jsoup = "org.jsoup" % "jsoup" % "1.14.3"

  lazy val jodaTime = "joda-time" % "joda-time" % "2.10.13"

  lazy val pureconfig = "com.github.pureconfig" %% "pureconfig" % "0.17.1"

  lazy val flywayCore = "org.flywaydb" % "flyway-core" % "8.5.4"

  lazy val postgresql = "org.postgresql" % "postgresql" % "42.3.3"

  lazy val h2 = "com.h2database" % "h2" % "2.1.210"

  lazy val apacheTika = "org.apache.tika" % "tika-core" % "2.3.0"

  lazy val redis4CatsEffects = "dev.profunktor" %% "redis4cats-effects" % "1.1.1"

  lazy val jbcrypt = "org.mindrot" % "jbcrypt" % "0.4"

  lazy val embeddedRedis = "com.github.kstyrc" % "embedded-redis" % "0.6"

  lazy val embeddedKafkaSchemaRegistry = "io.github.embeddedkafka" %% "embedded-kafka-schema-registry" % "7.0.1"

  lazy val testContainers = "org.testcontainers" % "testcontainers" % TestContainersVersion

  lazy val kafkaTestContainer = "org.testcontainers" % "kafka" % TestContainersVersion

  lazy val postgresqlTestContainer = "org.testcontainers" % "postgresql" % TestContainersVersion

  lazy val logbackClassic = "ch.qos.logback" % "logback-classic" % "1.2.11"

  lazy val scalaLogging = "com.typesafe.scala-logging" %% "scala-logging" % "3.9.4"

  lazy val kindProjector = "org.typelevel" %% "kind-projector" % "0.13.2" cross CrossVersion.full

  lazy val betterMonadicFor = "com.olegpy" %% "better-monadic-for" % "0.3.1"

  lazy val scalaTest = "org.scalatest" %% "scalatest" % "3.2.11"

  lazy val scalaMock = "org.scalamock" %% "scalamock" % "5.2.0"

  lazy val pegdown = "org.pegdown" % "pegdown" % "1.6.0"
}
