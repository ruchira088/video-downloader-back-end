import sbt._

object Dependencies
{
  val ScalaVersion = "2.13.3"
  val Http4sVersion = "0.21.8"
  val CirceVersion = "0.13.0"

  lazy val http4sDsl = "org.http4s" %% "http4s-dsl" % Http4sVersion

  lazy val http4sBlazeServer = "org.http4s" %% "http4s-blaze-server" % Http4sVersion

  lazy val http4sBlazeClient = "org.http4s" %% "http4s-blaze-client" % Http4sVersion

  lazy val http4sCirce = "org.http4s" %% "http4s-circe" % Http4sVersion

  lazy val catsEffect = "org.typelevel" %% "cats-effect" % "2.2.0"

  lazy val fs2Io = "co.fs2" %% "fs2-io" % "2.3.0"

  lazy val circeGeneric = "io.circe" %% "circe-generic" % CirceVersion

  lazy val circeParser = "io.circe" %% "circe-parser" % CirceVersion

  lazy val circeLiteral = "io.circe" %% "circe-literal" % CirceVersion

  lazy val enumeratum = "com.beachape" %% "enumeratum" % "1.6.1"

  lazy val shapeless = "com.chuusai" %% "shapeless" % "2.3.3"

  lazy val doobie = "org.tpolecat" %% "doobie-core" % "0.9.2"

  lazy val jsoup = "org.jsoup" % "jsoup" % "1.13.1"

  lazy val jodaTime = "joda-time" % "joda-time" % "2.10.6"

  lazy val pureconfig = "com.github.pureconfig" %% "pureconfig" % "0.14.0"

  lazy val flywayCore = "org.flywaydb" % "flyway-core" % "7.0.4"

  lazy val postgresql = "org.postgresql" % "postgresql" % "42.2.18"

  lazy val h2 = "com.h2database" % "h2" % "1.4.200"

  lazy val jcodec = "org.jcodec" % "jcodec" % "0.2.5"

  lazy val jcodecJavaSe = "org.jcodec" % "jcodec-javase" % "0.2.5"

  lazy val thumbnailator = "net.coobird" % "thumbnailator" % "0.4.12"

  lazy val apacheTika = "org.apache.tika" % "tika-core" % "1.24.1"

  lazy val redis4CatsEffects = "dev.profunktor" %% "redis4cats-effects" % "0.10.3"

  lazy val jbcrypt = "org.mindrot" % "jbcrypt" % "0.4"

  lazy val logbackClassic = "ch.qos.logback" % "logback-classic" % "1.2.3"

  lazy val scalaLogging = "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2"

  lazy val kindProjector = "org.typelevel" %% "kind-projector" % "0.11.0" cross CrossVersion.full

  lazy val scalaTypedHoles = "com.github.cb372" % "scala-typed-holes" % "0.1.5" cross CrossVersion.full

  lazy val betterMonadicFor = "com.olegpy" %% "better-monadic-for" % "0.3.1"

  lazy val scalaTest = "org.scalatest" %% "scalatest" % "3.2.2"

  lazy val scalaMock = "org.scalamock" %% "scalamock" % "5.0.0"

  lazy val pegdown = "org.pegdown" % "pegdown" % "1.6.0"
}
