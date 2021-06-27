import sbt._

object Dependencies
{
  val ScalaVersion = "2.13.6"
  val Http4sVersion = "0.21.24"
  val CirceVersion = "0.14.1"

  lazy val http4sDsl = "org.http4s" %% "http4s-dsl" % Http4sVersion

  lazy val http4sBlazeServer = "org.http4s" %% "http4s-blaze-server" % Http4sVersion

  lazy val http4sAsyncHttpClient = "org.http4s" %% "http4s-async-http-client" % Http4sVersion

  lazy val http4sCirce = "org.http4s" %% "http4s-circe" % Http4sVersion

  lazy val catsEffect = "org.typelevel" %% "cats-effect" % "2.5.1"

  lazy val fs2Io = "co.fs2" %% "fs2-io" % "2.3.1"

  lazy val fs2Kafka = "com.github.fd4s" %% "fs2-kafka" % "1.7.0"

  lazy val fs2KafkaVulkan = "com.github.fd4s" %% "fs2-kafka-vulcan" % "1.7.0"

  lazy val vulkanGeneric = "com.github.fd4s" %% "vulcan-generic" % "1.7.1"

  lazy val circeGeneric = "io.circe" %% "circe-generic" % CirceVersion

  lazy val circeParser = "io.circe" %% "circe-parser" % CirceVersion

  lazy val circeLiteral = "io.circe" %% "circe-literal" % CirceVersion

  lazy val enumeratum = "com.beachape" %% "enumeratum" % "1.7.0"

  lazy val shapeless = "com.chuusai" %% "shapeless" % "2.3.7"

  lazy val doobie = "org.tpolecat" %% "doobie-core" % "0.13.4"

  lazy val doobieHikari = "org.tpolecat" %% "doobie-hikari" % "0.13.4"

  lazy val jsoup = "org.jsoup" % "jsoup" % "1.13.1"

  lazy val jodaTime = "joda-time" % "joda-time" % "2.10.10"

  lazy val pureconfig = "com.github.pureconfig" %% "pureconfig" % "0.16.0"

  lazy val flywayCore = "org.flywaydb" % "flyway-core" % "7.10.0"

  lazy val postgresql = "org.postgresql" % "postgresql" % "42.2.22"

  lazy val h2 = "com.h2database" % "h2" % "1.4.200"

  lazy val jcodec = "org.jcodec" % "jcodec" % "0.2.5"

  lazy val jcodecJavaSe = "org.jcodec" % "jcodec-javase" % "0.2.5"

  lazy val thumbnailator = "net.coobird" % "thumbnailator" % "0.4.14"

  lazy val apacheTika = "org.apache.tika" % "tika-core" % "1.26"

  lazy val redis4CatsEffects = "dev.profunktor" %% "redis4cats-effects" % "0.13.1"

  lazy val jbcrypt = "org.mindrot" % "jbcrypt" % "0.4"

  lazy val embeddedRedis = "com.github.kstyrc" % "embedded-redis" % "0.6"

  lazy val embeddedKafkaSchemaRegistry = "io.github.embeddedkafka" %% "embedded-kafka-schema-registry" % "6.2.0"

  lazy val logbackClassic = "ch.qos.logback" % "logback-classic" % "1.2.3"

  lazy val scalaLogging = "com.typesafe.scala-logging" %% "scala-logging" % "3.9.4"

  lazy val kindProjector = "org.typelevel" %% "kind-projector" % "0.13.0" cross CrossVersion.full

  lazy val scalaTypedHoles = "com.github.cb372" % "scala-typed-holes" % "0.1.9" cross CrossVersion.full

  lazy val betterMonadicFor = "com.olegpy" %% "better-monadic-for" % "0.3.1"

  lazy val scalaTest = "org.scalatest" %% "scalatest" % "3.2.9"

  lazy val scalaMock = "org.scalamock" %% "scalamock" % "5.1.0"

  lazy val pegdown = "org.pegdown" % "pegdown" % "1.6.0"
}
